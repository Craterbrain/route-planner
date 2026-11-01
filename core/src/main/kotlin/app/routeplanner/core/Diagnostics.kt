package app.routeplanner.core

/**
 * Crash reports that are safe to hand to someone else. An exception's message can hold what the driver typed or an address
 * ("Couldn't find “469 Fair Oaks Rd”", a URL with a search query), so reports carry only the classes and the code locations
 * (file and line): enough to find the bug, nothing about the data.
 */
object Diagnostics {
    private const val MAX_CAUSES = 5

    fun describe(t: Throwable, maxFrames: Int = 30): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        val seen = HashSet<Throwable>()
        while (cur != null && depth < MAX_CAUSES && seen.add(cur)) {
            sb.append(if (depth == 0) "" else "Caused by: ").append(cur.javaClass.name).append(" [message omitted]\n")
            for (f in cur.stackTrace.take(maxFrames)) {
                sb.append("    at ").append(f.className).append('.').append(f.methodName)
                sb.append('(').append(f.fileName ?: "?").append(if (f.lineNumber >= 0) ":${f.lineNumber}" else "").append(")\n")
            }
            if (cur.stackTrace.size > maxFrames) sb.append("    ... ${cur.stackTrace.size - maxFrames} more\n")
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }
}
