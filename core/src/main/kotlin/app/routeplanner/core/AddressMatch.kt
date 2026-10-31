package app.routeplanner.core

import app.routeplanner.core.address.AddrNorm

/**
 * Does a geocoder's result really say the address that was typed or scanned? Used to confirm a label
 * address before adding it without asking: the house number must be the same, the street must be the
 * same road once spelling is normalized ("Hwy 70 E" = "Highway 70 East"), and a ZIP, when both have one,
 * must agree. A different city is not checked (labels often carry the mailing city, maps the town).
 */
object AddressMatch {
    private val UNIT_WORDS = setOf("apt", "apartment", "unit", "ste", "suite", "lot", "bldg", "rm", "room", "#")
    private val ZIP = Regex("""\b(\d{5})(?:-\d{4})?\b""")

    private class Parts(val number: String, val street: Set<String>, val zip: String?)

    private fun parts(text: String): Parts? {
        val streetPart = text.substringBefore(',').replace("#", " # ")
        val words = streetPart.split(' ', '\t').filter { it.isNotEmpty() }
        if (words.size < 2 || !words[0].first().isDigit()) return null
        var end = words.size
        for (i in 1 until words.size) if (words[i].lowercase().trim('.') in UNIT_WORDS) { end = i; break }
        val number = words[0].lowercase().trimEnd(',', '.')
        val tokens = AddrNorm.normalizeStreet(words.subList(1, end).joinToString(" ")).split(' ').filter { it.isNotEmpty() && it !in AddrNorm.DIRS }
        if (tokens.isEmpty()) return null
        return Parts(number, tokens.toSet(), ZIP.findAll(text).lastOrNull()?.groupValues?.get(1))
    }

    fun matches(typed: String, found: Suggestion): Boolean {
        if (found.point == null) return false
        val t = parts(typed) ?: return false
        val f = parts(found.title) ?: parts(found.label) ?: return false
        if (t.number != f.number) return false
        if (!(t.street.containsAll(f.street) || f.street.containsAll(t.street))) return false
        val fz = f.zip ?: ZIP.findAll(found.label).lastOrNull()?.groupValues?.get(1)
        if (t.zip != null && fz != null && t.zip != fz) return false
        return true
    }
}
