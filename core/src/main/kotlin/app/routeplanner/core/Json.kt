package app.routeplanner.core

/**
 * Tiny JSON reader so `core` stays dependency-free (org.json is Android-only).
 * Returns Map<String, Any?>, List<Any?>, Double, String, Boolean or null.
 */
object Json {
    fun parse(text: String): Any? = Reader(text).run { skip(); value().also { skip(); check(i == s.length) { "trailing data at $i" } } }

    private class Reader(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) { check(s.getOrNull(i) == c) { "expected '$c' at $i" }; i++ }

        fun value(): Any? {
            skip()
            return when (val c = s.getOrNull(i)) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c?.isDigit() == true) num() else error("unexpected '$c' at $i")
            }
        }

        fun lit(w: String, v: Any?): Any? { check(s.startsWith(w, i)) { "bad literal at $i" }; i += w.length; return v }

        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s.getOrElse(i++) { error("unterminated string") }
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            expect('['); skip()
            val out = ArrayList<Any?>()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value(); skip()
                if (s[i] == ',') i++ else { expect(']'); return out }
            }
        }

        fun obj(): Map<String, Any?> {
            expect('{'); skip()
            val out = LinkedHashMap<String, Any?>()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skip(); val k = str(); skip(); expect(':')
                out[k] = value(); skip()
                if (s[i] == ',') i++ else { expect('}'); return out }
            }
        }
    }
}
