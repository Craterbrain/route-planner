package app.routeplanner.core

/**
 * Pulls delivery addresses and tracking numbers out of text read from a shipping label
 * (OCR output, one line per line). Heuristic and US-oriented: a street line (house number +
 * street words) followed, optionally after a unit line, by a "CITY ST 12345" line.
 */
object LabelParser {
    class Result(val addresses: List<String>, val tracking: List<String>)

    private val STATES = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME",
        "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
        "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "PR",
    )
    private val CITY_LINE = Regex("""^(.*?[A-Za-z].*?)[,\s]+([A-Za-z]{2})\s+(\d{5})(?:-\d{4})?$""")
    private val STREET_LINE = Regex("""^\d+[A-Za-z]?(?:\s+|-)\S.*[A-Za-z].*$""")
    private val UNIT_LINE = Regex("""^(?:APT|APARTMENT|UNIT|STE|SUITE|LOT|BLDG|#)\.?\s*\S+.*$""", RegexOption.IGNORE_CASE)
    private val PHONE_LIKE = Regex("""^\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$""")

    private val UPS = Regex("""\b1Z[0-9A-Z]{16}\b""", RegexOption.IGNORE_CASE)
    private val DIGIT_RUN = Regex("""(?<!\d)(?:\d[ -]?){12,34}(?!\d)""")

    fun parse(text: String): Result {
        val lines = text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
        return Result(addresses(lines), tracking(text))
    }

    fun tracking(text: String): List<String> {
        val found = LinkedHashSet<String>()
        UPS.findAll(text).forEach { found += it.value.uppercase() }
        for (m in DIGIT_RUN.findAll(text)) {
            val d = m.value.filter { it.isDigit() }
            val ok = when (d.length) {
                12, 15 -> true                                   // FedEx Express / Ground
                20, 22 -> d.startsWith("9") || d.startsWith("96") // USPS IMpb / FedEx SmartPost-style
                26, 30, 34 -> d.startsWith("420") || d.startsWith("92") || d.startsWith("93") || d.startsWith("94") || d.startsWith("95")
                else -> false
            }
            if (ok) found += d
        }
        return found.toList()
    }

    private fun addresses(lines: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for ((i, line) in lines.withIndex()) {
            // Street and city on one line: "8681 Hwy 70 E, Crossville TN 38555".
            cityParts(line)?.let { (_, _) ->
                val comma = line.indexOf(',')
                if (comma > 0 && STREET_LINE.matches(line.substring(0, comma).trim())) out += pretty(line)
            }
            if (!STREET_LINE.matches(line) || PHONE_LIKE.matches(line) || cityParts(line) != null) continue
            var j = i + 1
            var unit: String? = null
            if (j < lines.size && UNIT_LINE.matches(lines[j])) { unit = lines[j]; j++ }
            val next = lines.getOrNull(j) ?: continue
            if (cityParts(next) == null) continue
            out += pretty(listOfNotNull(line, unit).joinToString(" ") + ", " + next)
        }
        return out.toList()
    }

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
