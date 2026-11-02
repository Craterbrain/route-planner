package app.routeplanner.core

/**
 * Shared by the live scanner, the label bench app and the JVM tests: which text lines belong to the label and in what order.
 * Pure Kotlin on purpose, so the exact code that runs on the phone can be replayed on a PC against recorded recognizer output.
 */
object LabelReading {
    /** A text line's bounding box in the upright image, in pixels. */
    class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    /**
     * Lines top to bottom, taken one by one rather than block by block: the recognizer's own grouping can put a wider line (the
     * city line with its ZIP+4) ahead of the street line above it, and then no address is found. Lines that share a row
     * (a box printed beside an address) go left to right.
     */
    fun <T> order(lines: List<T>, box: (T) -> Box?): List<T> {
        val sorted = lines.filter { box(it) != null }.sortedBy { box(it)!!.centerY }
        val out = ArrayList<T>()
        var row = ArrayList<T>()
        fun flush() { out += row.sortedBy { box(it)!!.left }; row = ArrayList() }
        for (l in sorted) {
            val b = box(l)!!
            // Same row only when it sits beside a line already in the row: nearly the same height on the page and no
            // horizontal overlap with it. Stacked lines (name above street above city) overlap horizontally and so are
            // never merged, however tightly they are set or tilted.
            val beside = row.isNotEmpty() && row.all { r ->
                val rb = box(r)!!
                val noOverlap = b.right <= rb.left || b.left >= rb.right
                noOverlap && Math.abs(b.centerY - rb.centerY) < 0.3f * minOf(b.height, rb.height)
            }
            if (!beside) flush()
            row += l
        }
        flush()
        return out
    }

    /** The label's bright patch on a coarse brightness grid, in sensor-frame pixels. */
    class Region(val width: Int, val height: Int, val cell: Int, val gw: Int, val gh: Int, val mask: BooleanArray, val rotation: Int) {
        /** True when the upright-frame point ([ux], [uy]) is on the label (or within two grid cells of it: text is darker than paper). */
        fun contains(ux: Int, uy: Int): Boolean {
            val (x, y) = when (rotation) {
                90 -> uy to height - ux
                180 -> width - ux to height - uy
                270 -> width - uy to ux
                else -> ux to uy
            }
            val cx = x / cell; val cy = y / cell
            for (dy in -2..2) for (dx in -2..2) {
                val nx = cx + dx; val ny = cy + dy
                if (nx in 0 until gw && ny in 0 until gh && mask[ny * gw + nx]) return true
            }
            return false
        }
    }

    private fun otsu(values: IntArray): Pair<Int, Double> {
        val hist = IntArray(256); values.forEach { hist[it.coerceIn(0, 255)]++ }
        val n = values.size
        var sumAll = 0L; for (t in 0..255) sumAll += t.toLong() * hist[t]
        var wB = 0; var sumB = 0L; var best = 0.0; var thr = 128
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0) continue
            val wF = n - wB; if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB; val mF = (sumAll - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        return thr to best / (n.toDouble() * n)
    }

    /**
     * The bright label in the frame (null when none stands out). A label is the largest bright, high-contrast patch: a second
     * brightness cut inside the already-bright area separates white paper from a glowing screen or a lit table. Everything
     * outside it (the table, other parcels, a screen's other windows) is not part of the label and its text must not be read
     * as an address. [luma] gives 0..255 brightness at a pixel of the sensor frame.
     */
    fun labelRegion(width: Int, height: Int, rotation: Int, luma: (x: Int, y: Int) -> Int): Region? {
        val gw = 64
        val cell = maxOf(1, width / gw)
        val gh = height / cell
        if (gh < 8) return null
        val g = IntArray(gw * gh)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            var sum = 0
            val x0 = cx * cell; val y0 = cy * cell
            for (dy in 0..1) for (dx in 0..1) sum += luma(minOf(width - 1, x0 + dx * cell / 2 + cell / 4), minOf(height - 1, y0 + dy * cell / 2 + cell / 4))
            g[cy * gw + cx] = sum / 4
        }
        var (thr, contrast) = otsu(g)
        if (contrast < 40.0) return null                      // flat frame: nothing to separate
        // Second cut: among the bright cells, the whitest ones.
        val bright = g.filter { it > thr }.toIntArray()
        if (bright.size > g.size / 6) { val (t2, c2) = otsu(bright); if (c2 > 20.0) thr = t2 }
        val seen = BooleanArray(g.size)
        val stack = IntArray(g.size)
        var bestArea = 0; var bestCells: IntArray? = null
        for (start in g.indices) {
            if (seen[start] || g[start] <= thr) continue
            val cells = ArrayList<Int>()
            var sp = 0; stack[sp++] = start; seen[start] = true
            while (sp > 0) {
                val c = stack[--sp]; cells += c; val cx = c % gw; val cy = c / gw
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val n = ny * gw + nx
                    if (!seen[n] && g[n] > thr) { seen[n] = true; stack[sp++] = n }
                }
            }
            if (cells.size > bestArea) { bestArea = cells.size; bestCells = cells.toIntArray() }
        }
        val cells = bestCells ?: return null
        val frac = bestArea.toDouble() / g.size
        if (frac < 0.08 || frac > 0.92) return null           // too small to be a label, or the whole frame is "bright"
        val mask = BooleanArray(g.size); cells.forEach { mask[it] = true }
        // Print is darker than blank paper, so a block of dense text (the address) can fall below the cut and leave a hole in the
        // label's shape. Anything not connected to the frame's edge through non-label cells is inside the label: fill it.
        val outside = BooleanArray(g.size)
        var sp = 0
        fun seed(i: Int) { if (!mask[i] && !outside[i]) { outside[i] = true; stack[sp++] = i } }
        for (x in 0 until gw) { seed(x); seed((gh - 1) * gw + x) }
        for (y in 0 until gh) { seed(y * gw); seed(y * gw + gw - 1) }
        while (sp > 0) {
            val c = stack[--sp]; val cx = c % gw; val cy = c / gw
            if (cx > 0) seed(c - 1); if (cx < gw - 1) seed(c + 1); if (cy > 0) seed(c - gw); if (cy < gh - 1) seed(c + gw)
        }
        for (i in mask.indices) if (!outside[i]) mask[i] = true
        return Region(width, height, cell, gw, gh, mask, rotation)
    }

    /** Lines whose centre lies on the label region (all of them when there is none). */
    fun <T> inside(lines: List<T>, region: Region?, box: (T) -> Box?): List<T> =
        if (region == null) lines else lines.filter { l -> box(l)?.let { region.contains(it.centerX, it.centerY) } ?: false }
}
