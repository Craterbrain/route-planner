package app.routeplanner.core

/**
 * Pulls delivery addresses out of text read from a shipping label (OCR lines, top to bottom) and ranks
 * them: the delivery address first, the return address after it. Barcodes and tracking numbers are ignored.
 *
 * An address is a street line (house number + street words) followed, optionally after a unit line, by a
 * "CITY ST 12345" line, or both on one line. Heuristic and US-oriented.
 *
 * Which one is the delivery address:
 *  - cue words before the address: FROM / RETURN / SENDER / SHIP FROM mark the return address;
 *    TO / SHIP TO / DELIVER TO / ATTN mark the delivery address;
 *  - otherwise print size: the recipient's address is set bigger than the return address, so a clearly
 *    smaller address on the same label (below [SMALLER]) is treated as the return address.
 */
object LabelParser {
    /** One recognized line of text with its printed height (any consistent unit; 0 = unknown). */
    data class OcrLine(val text: String, val height: Float = 0f)

    enum class Role { DELIVERY, RETURN, UNKNOWN }

    class Candidate(val address: String, val role: Role, val height: Float) {
        /** True when this should be offered as the secondary "return address". */
        val isReturn get() = role == Role.RETURN
    }

    class Result(val candidates: List<Candidate>) {
        val addresses: List<String> get() = candidates.map { it.address }
        /** The address to add by default, or null if the label held none. */
        val primary: Candidate? get() = candidates.firstOrNull()
    }

    /** An address printed under this fraction of the largest address's height counts as the return address. */
    const val SMALLER = 0.8f

    private val STATES = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME",
        "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
        "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "PR",
    )
    private val CITY_LINE = Regex("""^(.*?[A-Za-z].*?)[,\s]+([A-Za-z]{2})\s+(\d{5})(?:-\d{4})?$""")
    private val STREET_LINE = Regex("""^\d+[A-Za-z]?(?:\s+|-)\S.*[A-Za-z].*$""")
    private val UNIT_LINE = Regex("""^(?:APT|APARTMENT|UNIT|STE|SUITE|LOT|BLDG|#)\.?\s*\S+.*$""", RegexOption.IGNORE_CASE)
    private val PHONE_LIKE = Regex("""^\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$""")
    private val RETURN_CUE = Regex("""^(?:RETURN(?:\s+ADDRESS|\s+TO)?|FROM|SENDER|SHIPPER|SHIP\s+FROM)\b""", RegexOption.IGNORE_CASE)
    private val DELIVER_CUE = Regex("""^(?:SHIP\s+TO|DELIVER(?:\s+TO)?|SEND\s+TO|ATTN|TO)\b""", RegexOption.IGNORE_CASE)

    /** Text only (no sizes): ranking then relies on cue words and order. */
    fun parse(text: String): Result = parse(text.lines().map { OcrLine(it) })

    fun parse(raw: List<OcrLine>): Result {
        val lines = raw.map { OcrLine(it.text.replace(Regex("""\s+"""), " ").trim(), it.height) }.filter { it.text.isNotEmpty() }
        val found = ArrayList<Candidate>()
        val seen = HashSet<String>()
        fun add(text: String, startLine: Int, height: Float) {
            val pretty = pretty(text)
            if (!seen.add(pretty.lowercase())) return
            found += Candidate(pretty, roleFromCues(lines, startLine), height)
        }
        for ((i, line) in lines.withIndex()) {
            val t = line.text
            // Street and city on one line: "8681 Hwy 70 E, Crossville TN 38555".
            if (cityParts(t) != null) {
                val comma = t.indexOf(',')
                if (comma > 0 && STREET_LINE.matches(t.substring(0, comma).trim())) add(t, i, line.height)
                continue
            }
            if (!STREET_LINE.matches(t) || PHONE_LIKE.matches(t)) continue
            var j = i + 1
            var unit: String? = null
            if (j < lines.size && UNIT_LINE.matches(lines[j].text)) { unit = lines[j].text; j++ }
            val next = lines.getOrNull(j) ?: continue
            if (cityParts(next.text) == null) continue
            add(listOfNotNull(t, unit).joinToString(" ") + ", " + next.text, i, line.height)
        }
        return Result(rank(found))
    }

    /** Cue words within the three lines above (and on) the address line decide its role. */
    private fun roleFromCues(lines: List<OcrLine>, at: Int): Role {
        for (k in at downTo maxOf(0, at - 3)) {
            val t = lines[k].text
            // A line that is itself an address (or the previous address's city line) ends the search.
            if (k < at && (cityParts(t) != null)) break
            if (RETURN_CUE.containsMatchIn(t)) return Role.RETURN
            if (DELIVER_CUE.containsMatchIn(t)) return Role.DELIVERY
        }
        return Role.UNKNOWN
    }

    /** Delivery first, return last; between equals the bigger print wins, then reading order. */
    private fun rank(found: List<Candidate>): List<Candidate> {
        if (found.isEmpty()) return found
        val maxH = found.maxOf { it.height }
        // Without cue words, an address clearly smaller than the biggest one is the return address.
        val resolved = found.map { c ->
            if (c.role == Role.UNKNOWN && found.size > 1 && maxH > 0f && c.height > 0f && c.height < maxH * SMALLER) Candidate(c.address, Role.RETURN, c.height) else c
        }
        return resolved.withIndex().sortedWith(
            compareBy<IndexedValue<Candidate>> { roleOrder(it.value.role) }.thenByDescending { it.value.height }.thenBy { it.index }
        ).map { it.value }
    }

    private fun roleOrder(r: Role) = when (r) { Role.DELIVERY -> 0; Role.UNKNOWN -> 1; Role.RETURN -> 2 }

    /** (city, state+zip) when [line] ends in "ST 12345" with a real state code. */
    private fun cityParts(line: String): Pair<String, String>? {
        val m = CITY_LINE.matchEntire(line) ?: return null
        val st = m.groupValues[2].uppercase()
        if (st !in STATES) return null
        return m.groupValues[1].trim() to "$st ${m.groupValues[3]}"
    }

    /** ALL-CAPS labels become Title Case; the state code and unit designators stay upper-case. */
    private fun pretty(s: String): String {
        if (s.any { it.isLowerCase() }) return s
        return s.split(' ').joinToString(" ") { w ->
            when {
                w.length == 2 && w in STATES -> w
                w.length == 5 && w.all { it.isDigit() } -> w
                w.any { it.isDigit() } && w.none { it.isLetter() } -> w
                w.length <= 2 && w.all { it.isLetter() } && w in setOf("NE", "NW", "SE", "SW") -> w
                else -> w.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
    }
}
