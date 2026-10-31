package app.routeplanner.core

/**
 * Single-vehicle VRPTW-lite. Cost = travel + waiting + heavy lateness and
 * placement (first/last) ordering penalties. Construction by nearest feasible
 * neighbour, improved by 2-opt and or-opt on the penalized cost.
 *
 * All times are seconds on the route clock; [t0] is "now" on that clock, so a
 * mid-route re-plan keeps time windows meaningful.
 */
object Solver {
    private const val LATE_PENALTY = 10.0
    private const val PLACEMENT_PENALTY = 1e6

    data class Eta(val arrival: Double, val late: Double)
    data class Eval(val cost: Double, val etas: List<Eta>, val totalSec: Double)

    /**
     * [totalSec] is the finish time on the route clock (includes [t0] and any
     * end leg); [totalMeters] the driven distance (including the end leg);
     * [endLegSec]/[endLegMeters] the final leg to the end point (0 without one).
     */
    class Result(val stops: List<Stop>, val totalSec: Int, val lateStops: Int, val totalMeters: Int = 0,
                 val endLegSec: Int = 0, val endLegMeters: Int = 0)

    private fun rank(s: Stop) = s.placement.ordinal // FIRST < AUTO < LAST

    /**
     * [order] holds indexes into [stops]; matrix index 0 is the start, stop i is
     * i + 1, and [endIdx] (if any) is the end point, reached after the last stop.
     */
    fun evaluate(order: List<Int>, stops: List<Stop>, dur: Array<DoubleArray>, t0: Double = 0.0, endIdx: Int? = null): Eval {
        var t = t0
        var prev = 0
        var cost = 0.0
        var maxRank = 0
        var violations = 0
        val etas = ArrayList<Eta>(order.size)
        for (idx in order) {
            val s = stops[idx]
            val travel = dur[prev][idx + 1]
            t += travel
            cost += travel
            val open = s.window?.first?.toDouble() ?: Double.NEGATIVE_INFINITY
            val close = s.window?.last?.toDouble() ?: Double.POSITIVE_INFINITY
            if (t < open) { cost += open - t; t = open }
            val late = maxOf(0.0, t - close)
            cost += late * LATE_PENALTY
            etas += Eta(t, late)
            t += s.serviceSec
            val r = rank(s)
            if (r < maxRank) violations++ else maxRank = r
            prev = idx + 1
        }
        if (endIdx != null && order.isNotEmpty()) { val back = dur[prev][endIdx]; t += back; cost += back }
        return Eval(cost + violations * PLACEMENT_PENALTY, etas, t)
    }

    private fun construct(stops: List<Stop>, dur: Array<DoubleArray>, t0: Double): MutableList<Int> {
        val remaining = stops.indices.toMutableSet()
        val order = ArrayList<Int>()
        var prev = 0
        var t = t0
        while (remaining.isNotEmpty()) {
            val minRank = remaining.minOf { rank(stops[it]) }
            val pool = remaining.filter { rank(stops[it]) == minRank }
            var best = pool.first()
            var bestScore = Double.POSITIVE_INFINITY
            for (i in pool) {
                val s = stops[i]
                val arrive = t + dur[prev][i + 1]
                val open = s.window?.first?.toDouble() ?: Double.NEGATIVE_INFINITY
                val close = s.window?.last?.toDouble() ?: Double.POSITIVE_INFINITY
                var score = maxOf(arrive, open) - t
                if (arrive > close) score += 1e5
                if (close.isFinite()) score += (close - arrive) * 1e-3
                if (score < bestScore) { bestScore = score; best = i }
            }
            val s = stops[best]
            t = maxOf(t + dur[prev][best + 1], s.window?.first?.toDouble() ?: Double.NEGATIVE_INFINITY) + s.serviceSec
            prev = best + 1
            order += best
            remaining -= best
        }
        return order
    }

    /** Safety net for very long routes on a slow phone: local search stops improving after this long. */
    const val SEARCH_BUDGET_MS = 3000L

    /**
     * Same cost as [evaluate], without building any objects, and gives up as soon as the running cost reaches
     * [limit] (every term only ever adds, so a candidate that is already as bad as the best can't win).
     * Returns [Double.POSITIVE_INFINITY] when it gives up.
     */
    private class Costs(stops: List<Stop>, private val dur: Array<DoubleArray>, private val t0: Double, private val endIdx: Int?) {
        private val open = DoubleArray(stops.size) { stops[it].window?.first?.toDouble() ?: Double.NEGATIVE_INFINITY }
        private val close = DoubleArray(stops.size) { stops[it].window?.last?.toDouble() ?: Double.POSITIVE_INFINITY }
        private val service = DoubleArray(stops.size) { stops[it].serviceSec.toDouble() }
        private val rank = IntArray(stops.size) { rank(stops[it]) }

        fun cost(order: IntArray, limit: Double = Double.POSITIVE_INFINITY): Double {
            var t = t0
            var prev = 0
            var cost = 0.0
            var maxRank = 0
            for (idx in order) {
                val travel = dur[prev][idx + 1]
                t += travel
                cost += travel
                if (t < open[idx]) { cost += open[idx] - t; t = open[idx] }
                if (t > close[idx]) cost += (t - close[idx]) * LATE_PENALTY
                t += service[idx]
                val r = rank[idx]
                if (r < maxRank) cost += PLACEMENT_PENALTY else maxRank = r
                if (cost >= limit) return Double.POSITIVE_INFINITY
                prev = idx + 1
            }
            if (endIdx != null && order.isNotEmpty()) cost += dur[prev][endIdx]
            return if (cost >= limit) Double.POSITIVE_INFINITY else cost
        }
    }

    private fun reverse(a: IntArray, i: Int, j: Int) {
        var l = i; var r = j
        while (l < r) { val t = a[l]; a[l] = a[r]; a[r] = t; l++; r-- }
    }

    /** Takes the element at [from] out and puts it back at index [to] of the shortened array (what List.add(to, removeAt(from)) did). */
    private fun move(a: IntArray, from: Int, to: Int) {
        val x = a[from]
        if (from < to) System.arraycopy(a, from + 1, a, from, to - from) else System.arraycopy(a, to, a, to + 1, from - to)
        a[to] = x
    }

    fun solveOrder(stops: List<Stop>, dur: Array<DoubleArray>, t0: Double = 0.0, endIdx: Int? = null): List<Int> {
        if (stops.size < 2) return stops.indices.toList()
        val order = construct(stops, dur, t0).toIntArray()
        val costs = Costs(stops, dur, t0, endIdx)
        val n = order.size
        var best = costs.cost(order)
        val deadline = System.nanoTime() + SEARCH_BUDGET_MS * 1_000_000L
        var improved = true
        var rounds = 0
        while (improved && rounds++ < 100 && System.nanoTime() < deadline) {
            improved = false
            // 2-opt: reverse a stretch; keep it if the route got cheaper, otherwise undo.
            for (i in 0 until n - 1) for (j in i + 1 until n) {
                reverse(order, i, j)
                val c = costs.cost(order, best - 1e-9)
                if (c < best - 1e-9) { best = c; improved = true } else reverse(order, i, j)
            }
            // or-opt: move one stop elsewhere.
            for (i in 0 until n) for (j in 0 until n) {
                if (i == j) continue
                move(order, i, j)
                val c = costs.cost(order, best - 1e-9)
                if (c < best - 1e-9) { best = c; improved = true } else move(order, j, i)
            }
            if (System.nanoTime() >= deadline) break
        }
        return order.toList()
    }

    private fun points(start: LatLng, stops: List<Stop>, end: LatLng?) =
        listOf(start) + stops.map { it.access } + listOfNotNull(end)

    /**
     * Orders [stops] from [start] (the depot), filling original/optimized
     * index, ETA, lateness and leg distance. With [end] the route must finish
     * there (round trip: pass the start), and the order accounts for that leg.
     */
    fun optimize(start: LatLng, stops: List<Stop>, provider: MatrixProvider, t0: Int = 0, end: LatLng? = null): Result {
        val m = provider.matrix(points(start, stops, end))
        val endIdx = end?.let { stops.size + 1 }
        return build(stops, solveOrder(stops, m.durations, t0.toDouble(), endIdx), m, t0, endIdx)
    }

    /** Keeps the given order (manual reorder, "update route") and only recomputes ETAs and distances. */
    fun schedule(start: LatLng, stops: List<Stop>, provider: MatrixProvider, t0: Int = 0, end: LatLng? = null): Result {
        if (stops.isEmpty()) return Result(emptyList(), t0, 0, 0)
        val m = provider.matrix(points(start, stops, end))
        return build(stops, stops.indices.toList(), m, t0, end?.let { stops.size + 1 })
    }

    private fun build(stops: List<Stop>, order: List<Int>, m: Matrix, t0: Int, endIdx: Int? = null): Result {
        val ev = evaluate(order, stops, m.durations, t0.toDouble(), endIdx)
        var prev = 0
        var meters = 0.0
        val out = order.mapIndexed { k, idx ->
            val leg = m.distances[prev][idx + 1]
            meters += leg
            prev = idx + 1
            stops[idx].copy(
                originalIndex = idx + 1,
                optimizedIndex = k + 1,
                etaSec = ev.etas[k].arrival.toInt(),
                lateSec = ev.etas[k].late.toInt(),
                legMeters = leg.toInt(),
            )
        }
        val endSec = endIdx?.let { m.durations[prev][it] } ?: 0.0
        val endM = endIdx?.let { m.distances[prev][it] } ?: 0.0
        return Result(out, ev.totalSec.toInt(), ev.etas.count { it.late > 0 }, (meters + endM).toInt(), endSec.toInt(), endM.toInt())
    }
}
