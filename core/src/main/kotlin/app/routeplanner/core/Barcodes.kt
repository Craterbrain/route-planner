package app.routeplanner.core

/**
 * Matching what a camera reads from a parcel against the codes saved with the route's packages.
 * A shipping label carries several codes (a tracking number in Code 128, DataMatrix routing codes, ...) and
 * different readers format the same one differently (spaces, a "420"+ZIP prefix in front of a tracking number),
 * so values are compared after stripping everything but letters and digits, and a long value matches a longer
 * one that contains it.
 */
object Barcodes {
    /** Codes at least this long (after cleaning) are treated as identifying one parcel; shorter ones are routing noise. */
    const val LONG = 10

    fun clean(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

    /** Same code, however it was formatted. */
    fun same(a: String, b: String): Boolean {
        val x = clean(a); val y = clean(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        return minOf(x.length, y.length) >= LONG && (x.contains(y) || y.contains(x))
    }

    /** What matched for a scan: the package(s) it points to, and whether that is certain. */
    class Match(val packages: List<Pkg>, val certain: Boolean)

    /**
     * Which package(s) hold the [scanned] values. A long code in common identifies the package (certain); only
     * short codes in common are weak: the result lists every package sharing them and is marked uncertain.
     * Null when nothing matches.
     */
    fun find(packages: List<Pkg>, scanned: Collection<String>): Match? {
        val values = scanned.map { it.trim() }.filter { clean(it).isNotEmpty() }.distinct()
        if (values.isEmpty()) return null
        val strong = packages.filter { p -> p.allCodes().any { c -> clean(c).length >= LONG && values.any { same(it, c) } } }
        if (strong.isNotEmpty()) return Match(strong, certain = strong.size == 1)
        val weak = packages.filter { p -> p.allCodes().any { c -> values.any { same(it, c) } } }
        return if (weak.isEmpty()) null else Match(weak, certain = false)
    }

    /** The package id counts as a code too (typed or imported tracking numbers). */
    private fun Pkg.allCodes(): List<String> = codes + id
}
