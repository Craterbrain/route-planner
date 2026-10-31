package app.routeplanner.core.address

import java.text.Normalizer
import java.util.Locale

/**
 * Address normalization for the offline address pack, ported rule for rule from
 * tools/addrnorm.py (spec: docs/address-pack.md, "Normalization"). The typed text
 * must normalize to exactly the `norm` the pack builder stored, so this must pass
 * every vector in tools/address-normalize-cases.json (see AddrNormTest).
 */
object AddrNorm {
    private val DELETE_CHARS = setOf('\'', '’', '‘', '`', '´', '.')

    private val ORDINAL_WORDS = mapOf(
        "first" to "1st", "second" to "2nd", "third" to "3rd", "fourth" to "4th",
        "fifth" to "5th", "sixth" to "6th", "seventh" to "7th", "eighth" to "8th",
        "ninth" to "9th", "tenth" to "10th", "eleventh" to "11th", "twelfth" to "12th",
        "thirteenth" to "13th", "fourteenth" to "14th", "fifteenth" to "15th",
        "sixteenth" to "16th", "seventeenth" to "17th", "eighteenth" to "18th",
        "nineteenth" to "19th", "twentieth" to "20th", "thirtieth" to "30th",
        "fortieth" to "40th", "fiftieth" to "50th", "sixtieth" to "60th",
        "seventieth" to "70th", "eightieth" to "80th", "ninetieth" to "90th",
    )

    private val TENS_WORDS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    val DIRECTIONALS = mapOf(
        "north" to "n", "south" to "s", "east" to "e", "west" to "w",
        "northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw",
        "n" to "n", "s" to "s", "e" to "e", "w" to "w",
        "ne" to "ne", "nw" to "nw", "se" to "se", "sw" to "sw",
    )
    val DIRS: Set<String> = DIRECTIONALS.values.toSet()

    private val WORD_MAP = mapOf(
        "alley" to "aly", "aly" to "aly",
        "avenue" to "ave", "ave" to "ave", "av" to "ave", "aven" to "ave", "avn" to "ave",
        "bend" to "bnd", "bnd" to "bnd",
        "boulevard" to "blvd", "blvd" to "blvd", "boul" to "blvd", "blv" to "blvd",
        "bypass" to "byp", "byp" to "byp", "bypa" to "byp",
        "circle" to "cir", "cir" to "cir", "circ" to "cir", "crcl" to "cir",
        "court" to "ct", "ct" to "ct", "crt" to "ct",
        "cove" to "cv", "cv" to "cv",
        "creek" to "crk", "crk" to "crk",
        "crossing" to "xing", "xing" to "xing", "crssng" to "xing",
        "drive" to "dr", "dr" to "dr", "drv" to "dr",
        "expressway" to "expy", "expy" to "expy",
        "extension" to "ext", "ext" to "ext",
        "freeway" to "fwy", "fwy" to "fwy",
        "heights" to "hts", "hts" to "hts",
        "highway" to "hwy", "hwy" to "hwy", "hiway" to "hwy", "hway" to "hwy",
        "hollow" to "holw", "holw" to "holw", "hollw" to "holw",
        "lane" to "ln", "ln" to "ln",
        "loop" to "loop", "lp" to "loop",
        "parkway" to "pkwy", "pkwy" to "pkwy", "pky" to "pkwy", "pkway" to "pkwy",
        "pike" to "pike", "pk" to "pike", "pke" to "pike",
        "place" to "pl", "pl" to "pl",
        "plaza" to "plz", "plz" to "plz",
        "point" to "pt", "pt" to "pt",
        "ridge" to "rdg", "rdg" to "rdg",
        "road" to "rd", "rd" to "rd",
        "route" to "rte", "rte" to "rte", "rt" to "rte",
        "square" to "sq", "sq" to "sq",
        "street" to "st", "st" to "st", "str" to "st",
        "terrace" to "ter", "ter" to "ter", "terr" to "ter",
        "trace" to "trce", "trce" to "trce",
        "trail" to "trl", "trl" to "trl",
        "turnpike" to "tpke", "tpke" to "tpke",
        "way" to "way", "wy" to "way",
        "saint" to "st",
        "mount" to "mt", "mt" to "mt",
        "mountain" to "mtn", "mtn" to "mtn",
        "fort" to "ft", "ft" to "ft",
        "county" to "co",
        "interstate" to "i",
        "and" to "and",
    )

    internal val STREET_TYPES = setOf(
        "aly", "ave", "bnd", "blvd", "byp", "cir", "ct", "cv", "dr", "expy", "ext",
        "fwy", "hwy", "ln", "loop", "pkwy", "pike", "pl", "plz", "rd", "sq", "st",
        "ter", "trce", "trl", "tpke", "way", "xing",
    )
    private val ROUTE_WORDS = setOf("us", "hwy", "sr", "tn", "state", "rte")
    private val ORDINAL_SUFFIXES = setOf("st", "nd", "rd", "th")
    private val UNIT_WORDS = setOf(
        "apt", "apartment", "unit", "ste", "suite", "lot", "rm", "room",
        "no", "num", "bldg", "building", "trlr", "spc", "space",
    )

    private fun az(c: Char) = c in 'a'..'z'
    private fun digit(c: Char) = c in '0'..'9'
    fun isDigits(t: String) = t.isNotEmpty() && t.all { digit(it) }
    private fun isLetters(t: String) = t.isNotEmpty() && t.all { az(it) }

    /** Python's str.split(): split on whitespace, drop empties. */
    private fun splitWs(s: String): List<String> = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }

    /** Steps 1-6: fold accents, lowercase, delete/replace characters, split letter/digit runs. */
    fun tokenize(input: String?): List<String> {
        if (input.isNullOrEmpty()) return emptyList()
        val folded = Normalizer.normalize(input, Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (c in folded) {
            when {
                c in DELETE_CHARS -> {}
                c == '&' -> sb.append(" and ")
                az(c) || digit(c) -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        val tokens = ArrayList<String>()
        for (t in splitWs(sb.toString())) {
            var i = 0
            while (i < t.length && az(t[i])) i++
            if (i > 0 && i < t.length && isDigits(t.substring(i))) { tokens += t.substring(0, i); tokens += t.substring(i); continue }
            var j = 0
            while (j < t.length && digit(t[j])) j++
            if (j > 0 && j < t.length && isLetters(t.substring(j)) && t.substring(j) !in ORDINAL_SUFFIXES) {
                tokens += t.substring(0, j); tokens += t.substring(j); continue
            }
            tokens += t
        }
        val res = ArrayList<String>()
        for (t0 in tokens) {
            var t = t0
            if (isDigits(t)) t = t.trimStart('0').ifEmpty { "0" }
            if (t == "s" && res.isNotEmpty() && res.last() == "u") { res[res.size - 1] = "us"; continue }
            res += t
        }
        return res
    }

    /** Step 7: table lookups plus "twenty first" -> "21st". */
    fun mapTokens(tokens: List<String>): List<String> {
        val out = ArrayList<String>()
        for (t0 in tokens) {
            var t = ORDINAL_WORDS[t0] ?: t0
            t = DIRECTIONALS[t] ?: t
            t = WORD_MAP[t] ?: t
            if (out.isNotEmpty() && out.last() in TENS_WORDS && t.length == 3 && t[0] in '1'..'9' && t.substring(1) in ORDINAL_SUFFIXES) {
                out[out.size - 1] = (TENS_WORDS.getValue(out.last()) + (t[0] - '0')).toString() + t.substring(1)
                continue
            }
            out += t
        }
        return out
    }

    /** Canonical street key ("US-70 E" -> "hwy 70 e"). */
    fun normalizeStreet(s: String?): String {
        val toks = mapTokens(tokenize(s))
        val out = ArrayList<String>()
        var i = 0
        val n = toks.size
        while (i < n) {
            val t = toks[i]
            if (t == "co" && i + 2 < n && toks[i + 1] in setOf("rd", "hwy", "rte") && isDigits(toks[i + 2])) {
                out += "cr"; out += toks[i + 2]; i += 3; continue
            }
            if (t == "cr" && i + 1 < n && isDigits(toks[i + 1])) { out += "cr"; out += toks[i + 1]; i += 2; continue }
            if (t in ROUTE_WORDS) {
                var j = i
                while (j < n && toks[j] in ROUTE_WORDS) j++
                if (j < n && isDigits(toks[j])) { out += "hwy"; out += toks[j]; i = j + 1; continue }
            }
            out += t
            i++
        }
        val dedup = ArrayList<String>()
        for (t in out) {
            if (dedup.isNotEmpty() && t == dedup.last() && t in STREET_TYPES) continue
            dedup += t
        }
        var result: List<String> = dedup
        if (dedup.size >= 2 && dedup[0] in DIRS) {
            val rest = dedup.subList(1, dedup.size)
            if (rest.any { it !in STREET_TYPES && it !in DIRS }) result = rest + dedup[0]
        }
        return result.joinToString(" ")
    }

    /** City / locality tokens: steps 1-7 only. */
    fun normalizePlace(s: String?): List<String> = mapTokens(tokenize(s))

    /** House number key: lowercase, keep a-z 0-9 and '/'. */
    fun normalizeNumber(s: String?): String =
        if (s.isNullOrEmpty()) "" else s.lowercase(Locale.ROOT).filter { az(it) || digit(it) || it == '/' }

    /** Unit key: "Apt. 4-B" -> "4b". */
    fun normalizeUnit(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder()
        for (c in s.lowercase(Locale.ROOT)) sb.append(if (az(c) || digit(c) || c == '/') c else ' ')
        return splitWs(sb.toString()).filter { it !in UNIT_WORDS }.joinToString("")
    }

    data class House(val number: String, val num: Int, val unit: String)

    /** (number, num, unit) for a raw house number + unit; num = -1 without leading digits. */
    fun splitHouseNumber(number: String?, unit: String? = ""): House {
        val parts = splitWs(number ?: "")
        var main = if (parts.isNotEmpty()) normalizeNumber(parts[0]) else ""
        var suffix = parts.drop(1).joinToString(" ")
        var j = 0
        while (j < main.length && digit(main[j])) j++
        if (j > 0 && j < main.length && main.substring(j).all { az(it) }) {
            suffix = main.substring(j) + " " + suffix
            main = main.substring(0, j)
        }
        val sfx = normalizeUnit(suffix)
        var u = normalizeUnit(unit)
        if (sfx.isNotEmpty() && u.isNotEmpty() && u != sfx && !u.startsWith(sfx)) u = sfx + u
        else if (u.isEmpty()) u = sfx
        return House(main, leadingInt(main) ?: -1, u)
    }

    fun leadingInt(s: String): Int? {
        var j = 0
        while (j < s.length && digit(s[j])) j++
        return if (j > 0) s.substring(0, j).toBigInteger().min(Int.MAX_VALUE.toBigInteger()).toInt() else null
    }
}
