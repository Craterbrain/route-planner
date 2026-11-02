package app.routeplanner.core

/**
 * Pulls delivery addresses out of text read from a shipping label (OCR lines, top to bottom) and ranks
 * them: the delivery address first, the return address after it. Barcodes and tracking numbers are ignored.
 *
 * An address is a street line (house number + street words) followed, optionally after a unit line, by a
 * "CITY ST 12345" line, or both on one line. Heuristic and US-oriented.
 *
 * Which one is the delivery address (checked in this order):
 *  - cue words just above the address: FROM / RETURN / SENDER / SHIP FROM mark the return address;
 *    TO / SHIP TO / DELIVER TO / ATTN mark the delivery address. Mailer endorsements such as
 *    "ADDRESS SERVICE REQUESTED" or "RETURN SERVICE REQUESTED" are not cues: they sit between the two blocks;
 *  - print size: the recipient's address is set bigger than the return address (a real USPS label has the
 *    return block about 70% the size), so an address printed under [SMALLER] of the biggest one is the return address;
 *  - position: with similar sizes the lower address is the recipient's (the return block is printed above it).
 * Size is measured over all lines of the address, not just the street line, to be steady against one bad line.
 */
object LabelParser {
    /** One recognized line of text with its printed height (any consistent unit; 0 = unknown). */
    data class OcrLine(val text: String, val height: Float = 0f)

    enum class Role { DELIVERY, RETURN, UNKNOWN }

    /** [name] is the person or business line printed just above the street line, when there is one. */
    class Candidate(val address: String, val role: Role, val height: Float, val name: String? = null) {
        /** True when this should be offered as the secondary "return address". */
        val isReturn get() = role == Role.RETURN
    }

    class Result(val candidates: List<Candidate>) {
        val addresses: List<String> get() = candidates.map { it.address }
        /** The address to add by default, or null if the label held none. */
        val primary: Candidate? get() = candidates.firstOrNull()
    }

    /** An address printed under this fraction of the largest address's height counts as the return address. */
    const val SMALLER = 0.85f

    private val STATES = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME",
        "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
        "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "PR",
    )
    // ZIP+4 may be written with a hyphen, en dash or em dash, with or without spaces around it.
    // Whatever follows the five-digit ZIP on that line (the "-6923" extension, or OCR's idea of it: a dropped, mangled or
    // misread dash and digits, stray marks) is ignored, and removed from the address handed to the geocoder.
    private val CITY_LINE = Regex("""^(.*?[A-Za-z].*?)[,\s]+([A-Za-z]{1,3})\s+([0-9${'$'}SsOoIlB]{5})(?:[0-9]{4}(?![0-9])|(?![0-9]))[^\n]{0,16}$""")
    // "CROSSVILL, 38571-2850": the state was lost; the ZIP alone (five clean digits) is enough to find the town.
    private val CITY_ZIP_ONLY = Regex("""^([A-Za-z][A-Za-z .'-]*[A-Za-z]{3})[,\s]+(\d{5})(?:-?\d{4})?(?!\d)[^\n]{0,10}$""")
    // "CROSSVILLE T," / "TN 38571-2524": the city line broken in two with a stray letter left on the first half.
    private val CITY_STUB = Regex("""^([A-Za-z][A-Za-z .'-]*?[A-Za-z])[,.]?(?:\s+[A-Za-z][,.]?)?$""")
    private val ZIP_ONLY_LINE = Regex("""^\d{5}(?:\s?[-–—]\s?\d{4})?$""")
    private val STATE_ZIP_HEAD = Regex("""^[A-Za-z]{2}\s+\d{5}.*$""")
    private val ENDORSEMENT = Regex("""SERVICE\s+REQUESTED|ADDRESS\s+CORRECTION|FORWARDING""", RegexOption.IGNORE_CASE)
    private const val ENDORSEMENT_TEXT = "SERVICEREQUESTED"
    /** "ADDRESS SERVICE REQUESTED", also as OCR garbles it ("ADDRESS GERVICE REQL ESTED"): the letters hold the phrase to within three edits. */
    private fun isEndorsement(t: String): Boolean {
        if (ENDORSEMENT.containsMatchIn(t)) return true
        val l = t.uppercase().filter { it.isLetter() }
        if (l.length < 13) return false
        val n = ENDORSEMENT_TEXT.length
        for (w in (n - 2)..(n + 2)) for (i in 0..(l.length - w)) if (editDistance(ENDORSEMENT_TEXT, l.substring(i, i + w)) <= 3) return true
        return false
    }
    private val STATE_TAIL = Regex("""^.*[A-Za-z].*[,\s]([A-Za-z]{2})$""")
    private val ZIP_HEAD = Regex("""^\d{5}(?:\d{4}(?!\d)|(?!\d))[^\n]{0,16}$""")
    private val RETURN_ENDORSEMENT = Regex("""RETURN\s+SERVICE""", RegexOption.IGNORE_CASE)
    private val STREET_LINE = Regex("""^\d+[A-Za-z]?(?:\s+|-)\S.*[A-Za-z].*$""")
    private val UNIT_LINE = Regex("""^(?:APT|APARTMENT|UNIT|STE|SUITE|LOT|BLDG|#)\.?\s*\S+.*$""", RegexOption.IGNORE_CASE)
    private val PHONE_LIKE = Regex("""^\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$""")
    private val RETURN_CUE = Regex("""^(?:RETURN(?!\s+SERVICE)(?:\s+ADDRESS|\s+TO)?|FROM|SENDER|SHIPPER|SHIP\s+FROM)\b""", RegexOption.IGNORE_CASE)
    private val DELIVER_CUE = Regex("""^(?:SHIP\s+TO|DELIVER(?:\s+TO)?|SEND\s+TO|ATTN|TO)\b""", RegexOption.IGNORE_CASE)

    /** Text only (no sizes): ranking then relies on cue words and order. */
    fun parse(text: String): Result = parse(text.lines().map { OcrLine(it) })

    fun parse(raw: List<OcrLine>): Result {
        val cleaned = raw.map { OcrLine(clean(it.text), it.height) }.filter { it.text.isNotEmpty() }
        // OCR sometimes breaks the city line in two: "CROSSVILLE TN" / "38572-7882". Put it back together.
        val lines = ArrayList<OcrLine>()
        for (l in cleaned) {
            val prev = lines.lastOrNull()
            if (prev != null && STATE_TAIL.matches(prev.text) && ZIP_HEAD.matches(l.text)) lines[lines.size - 1] = OcrLine(prev.text + " " + l.text, (prev.height + l.height) / 2f)
            else if (prev != null && (STATE_ZIP_HEAD.matches(l.text) && fixState(l.text.substringBefore(' ')) != null || ZIP_ONLY_LINE.matches(l.text)) &&
                !STREET_LINE.matches(prev.text) && CITY_STUB.matches(prev.text) && prev.text.none { it.isDigit() } && prev.text.trim(',', ' ', '.').split(' ').size <= 4) {
                val m = CITY_STUB.matchEntire(prev.text)!!
                lines[lines.size - 1] = OcrLine(m.groupValues[1] + " " + l.text, (prev.height + l.height) / 2f)
            }
            else lines += l
        }
        val found = ArrayList<Candidate>()
        val seen = HashSet<String>()
        fun add(text: String, startLine: Int, used: List<OcrLine>) {
            val pretty = pretty(text)
            if (!seen.add(pretty.lowercase())) return
            val known = used.map { it.height }.filter { it > 0f }
            found += Candidate(pretty, roleFromCues(lines, startLine), if (known.isEmpty()) 0f else known.average().toFloat(), nameAbove(lines, startLine))
        }
        for ((i, line) in lines.withIndex()) {
            val t = line.text
            // Street and city on one line: "8681 Hwy 70 E, Crossville TN 38555".
            if (cityParts(t) != null) {
                val comma = t.indexOf(',')
                if (comma > 0 && STREET_LINE.matches(t.substring(0, comma).trim())) add(t.substring(0, comma).trim() + ", " + cleanCity(t.substring(comma + 1).trim()), i, listOf(line))
                continue
            }
            if (!STREET_LINE.matches(t) || PHONE_LIKE.matches(t)) continue
            // The city line follows the street (after an optional unit line). A few stray lines may sit between them:
            // other print beside the address (a postage box, text on the next parcel) interleaves with it in reading order.
            var unit: OcrLine? = null
            var next: OcrLine? = null
            var j = i + 1
            while (j < lines.size && j <= i + 4) {
                val l = lines[j].text
                if (cityParts(l) != null) { next = lines[j]; break }
                if (unit == null && UNIT_LINE.matches(l)) unit = lines[j]
                else if (STREET_LINE.matches(l)) break            // another street line: this one has no city of its own
                j++
            }
            val city = next ?: continue
            add(listOfNotNull(t, unit?.text).joinToString(" ") + ", " + cleanCity(city.text), i, listOfNotNull(line, unit, city))
        }
        return Result(rank(found))
    }

    // A stray mark or letter read from a neighbouring barcode / box edge glued in front of the house number ("E 17 KILLEARN CT").
    private val LEADING_JUNK = Regex("""^[^A-Za-z0-9#]*(?:[A-Za-z|\[\]]|[A-Za-z]{1,2}:)\s+(?=\d+[A-Za-z]?\s+[A-Za-z])""")
    // Marks inside the house number ("1(043", "292:") and bracket marks in the street ("(COURT"); a mark between ZIP digits ("38!571").
    private val NUMBER_MARK = Regex("""^(\d+)[(\[{|!:;.,'`]+(\d+)(?=\s)""")
    private val NUMBER_TAIL = Regex("""^(\d+)[:;.,'`]+(?=\s)""")
    private val ZIP_MARK = Regex("""(?<=\d)[!|'`](?=\d)""")
    private val LETTERISH = Regex("""^[A-Za-z01]+$""")

    /**
     * Tidies one OCR line: collapses spaces, drops a stray leading character before a house number, and reads 0 / 1 as the
     * letters O / I inside a word that is otherwise letters ("0CEOLA" -> "OCEOLA"). A leading house number and ordinals
     * ("10TH", "1ST") are left alone.
     */
    private fun clean(raw: String): String {
        var t = raw.replace(Regex("""\s+"""), " ").trim().replace(LEADING_JUNK, "").replace(NUMBER_MARK, "$1$2").replace(NUMBER_TAIL, "$1").replace(ZIP_MARK, "")
        if (t.firstOrNull()?.isDigit() == true) t = t.replace(Regex("""[(){}\[\]]"""), "").replace(Regex("""(?<=[A-Za-z])[:;]"""), "")
        if (t.none { it.isLetter() }) return t
        t = t.split(' ').withIndex().joinToString(" ") { (i, w) ->
            val letters = w.count { it.isLetter() }
            if (i > 0 && letters >= 2 && w.any { it == '0' || it == '1' } && LETTERISH.matches(w) && !ORDINAL.matches(w))
                w.replace('0', 'O').replace('1', 'I') else w
        }
        // A street type run into the name ("DAWNCT" -> "DAWN CT"): only for endings that are not also common word endings
        // (ST, RD, DR, AVE would split "FOREST", "HOWARD", "ELEVEN").
        t = t.replace(GLUED_SUFFIX) { m -> m.groupValues[1] + " " + m.groupValues[2].uppercase() }
        return t
    }
    private val GLUED_SUFFIX = Regex("""(?<=^\d{1,6}[A-Za-z]? (?:[A-Za-z]{2,20} )?)([A-Za-z]{3,}?)(CT|LN|CIR|TRL)$""", RegexOption.IGNORE_CASE)
    private val ORDINAL = Regex("""^\d+(?:ST|ND|RD|TH)$""", RegexOption.IGNORE_CASE)

    private val NAME_LINE = Regex("""^[A-Za-z][A-Za-z.'-]*(?: [A-Za-z][A-Za-z.'-]*){1,3}$""")

    /** The line right above the street line when it reads like a name (two to four words, letters only). */
    private fun nameAbove(lines: List<OcrLine>, at: Int): String? {
        val t = lines.getOrNull(at - 1)?.text ?: return null
        if (!NAME_LINE.matches(t) || isEndorsement(t) || RETURN_CUE.containsMatchIn(t) || DELIVER_CUE.containsMatchIn(t)) return null
        return if (t.any { it.isLowerCase() }) t else t.split(' ').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    /** Cue words within the three lines above (and on) the address line decide its role. */
    private fun roleFromCues(lines: List<OcrLine>, at: Int): Role {
        for (k in at downTo maxOf(0, at - 3)) {
            val t = lines[k].text
            // A line that is itself an address (or the previous address's city line) ends the search.
            if (k < at && (cityParts(t) != null)) break
            // "ADDRESS SERVICE REQUESTED" is printed between the return block and the recipient's: what follows it is the recipient.
            if (isEndorsement(t)) { if (k < at && !RETURN_ENDORSEMENT.containsMatchIn(t)) return Role.DELIVERY; continue }
            if (RETURN_CUE.containsMatchIn(t)) return Role.RETURN
            if (DELIVER_CUE.containsMatchIn(t)) return Role.DELIVERY
        }
        // "ADDRESS SERVICE REQUESTED" printed just below an address, before the next one, means this is the return block.
        for (k in at + 1..minOf(lines.size - 1, at + 5)) {
            val t = lines[k].text
            if (STREET_LINE.matches(t) || cityParts(t) != null && k > at + 2) break
            if (isEndorsement(t) && !RETURN_ENDORSEMENT.containsMatchIn(t)) return Role.RETURN
        }
        return Role.UNKNOWN
    }

    /**
     * Delivery first, return last. Between candidates of the same role the bigger print wins; print sizes
     * within ~7% of each other count as equal, and equal ones go lowest-first (the return block is printed
     * above the recipient's).
     */
    private fun rank(found: List<Candidate>): List<Candidate> {
        if (found.isEmpty()) return found
        val maxH = found.maxOf { it.height }
        // Without cue words, an address clearly smaller than the biggest one is the return address.
        val resolved = found.map { c ->
            if (c.role == Role.UNKNOWN && found.size > 1 && maxH > 0f && c.height > 0f && c.height < maxH * SMALLER) Candidate(c.address, Role.RETURN, c.height, c.name) else c
        }
        return resolved.withIndex().sortedWith(
            compareBy<IndexedValue<Candidate>> { roleOrder(it.value.role) }
                .thenByDescending { sizeScore(it.value.height, maxH) }
                .thenByDescending { it.index }
        ).map { it.value }
    }

    /** Size relative to the biggest address; anything within 7% of it counts as the same size. */
    private fun sizeScore(h: Float, maxH: Float): Float = if (maxH <= 0f) 0f else (h / maxH).let { if (it >= 0.93f) 1f else it }

    private fun roleOrder(r: Role) = when (r) { Role.DELIVERY -> 0; Role.UNKNOWN -> 1; Role.RETURN -> 2 }

    /** "CITY ST 12345" from a city line, without anything after the ZIP (the ZIP+4 extension only confuses a geocoder). */
    private fun cleanCity(line: String): String = cityParts(line)?.let { (city, stZip) -> "$city $stZip" } ?: line

    /** (city, state+zip) when [line] ends in "ST 12345" with a real state code. */
    private fun cityParts(line: String): Pair<String, String>? {
        if (!line.first().isDigit()) CITY_ZIP_ONLY.matchEntire(line)?.let { if (CITY_LINE.matchEntire(line)?.let { m -> fixState(m.groupValues[2]) } == null) return it.groupValues[1].trim() to it.groupValues[2] }
        val m = CITY_LINE.matchEntire(line) ?: return null
        val st = fixState(m.groupValues[2]) ?: return null
        // OCR can misread a digit of the ZIP ("$8555"): letters that look like digits are mapped back, and a ZIP that is still
        // not five digits is dropped (the city and state remain). At least three real digits are needed to call it a ZIP at all.
        val raw = m.groupValues[3]
        if (raw.count { it.isDigit() } < 3) return null
        val zip = raw.map { c -> when (c) { 'O', 'o' -> '0'; 'I', 'l' -> '1'; 'S', 's' -> '5'; 'B' -> '8'; else -> c } }.joinToString("")
        return m.groupValues[1].trim() to (if (zip.all { it.isDigit() }) "$st $zip" else st)
    }

    /**
     * A state code as printed, or as OCR garbled it: "TIN" and "TII" for TN. A token that is not a state is accepted only when
     * exactly one state is one edit away and shares its first letter (the first letter is the one that survives).
     */
    private fun fixState(tok: String): String? {
        val t = tok.replace(Regex("[Il1|]{2}"), "N").uppercase()
        if (t in STATES) return t
        if (t.length !in 2..3) return null
        val near = STATES.filter { it[0] == t[0] && editDistance(it, t) == 1 }
        return near.singleOrNull()
    }

    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    /** ALL-CAPS labels become Title Case; the state code and unit designators stay upper-case. */
    private fun pretty(s: String): String {
        if (s.any { it.isLowerCase() }) return s
        val words = s.split(' ')
        return words.withIndex().joinToString(" ") { (i, w) ->
            when {
                // Only the token just before the ZIP is the state ("CT" in "1407 KAWA CT" is a street type).
                w.length == 2 && w in STATES && (words.getOrNull(i + 1)?.let { it.length == 5 && it.all(Char::isDigit) } ?: (i > 1)) -> w
                w.length == 5 && w.all { it.isDigit() } -> w
                w.any { it.isDigit() } && w.none { it.isLetter() } -> w
                w.length <= 2 && w.all { it.isLetter() } && w in setOf("NE", "NW", "SE", "SW") -> w
                else -> w.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
    }
}
