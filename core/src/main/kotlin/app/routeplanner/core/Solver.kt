package app.routeplanner.core

/**
 * Single-vehicle VRPTW-lite. Cost = travel + waiting + heavy lateness and
 * priority-ordering penalties. Construction by nearest feasible neighbour,
 * improved by 2-opt and or-opt on the penalized cost.
 */
object Solver {
    private const val LATE_PENALTY = 10.0
    private const val PRIORITY_PENALTY = 1e6

    data class Eta(val arrival: Double, val late: Double)
    data class Eval(val cost: Double, val etas: List<Eta>, val totalSec: Double)

    class Result(val stops: List<Stop>, val totalSec: Int, val lateStops: Int)

    /** [order] holds indexes into [stops]; matrix index 0 is the start, stop i is i + 1. */
    fun evaluate(order: List<Int>, stops: List<Stop>, dur: Array<DoubleArray>): Eval {
        var t = 0.0
        var prev = 0
        var cost = 0.0
        var seenNonPriority = false
        var violations = 0
        val etas = ArrayList<Eta>(order.size)
        for (idx in order) {
            val s = stops[idx]
            val travel = dur[prev][idx + 1]
            t += travel
            cost += travel
            val open = s.window?.first?.toDouble() ?: 0.0
            val close = s.window?.last?.toDouble() ?: Double.POSITIVE_INFINITY
            if (t < open) { cost += open - t; t = open }
            val late = maxOf(0.0, t - close)
            cost += late * LATE_PENALTY
            etas += Eta(t, late)
            t += s.serviceSec
            if (s.priority) { if (seenNonPriority) violations++ } else seenNonPriority = true
            prev = idx + 1
        }
        return Eval(cost + violations * PRIORITY_PENALTY, etas, t)
    }

    private fun construct(stops: List<Stop>, dur: Array<DoubleArray>): MutableList<Int> {
        val remaining = stops.indices.toMutableSet()
        val order = ArrayList<Int>()
        var prev = 0
        var t = 0.0
        while (remaining.isNotEmpty()) {
            val pool = remaining.filter { stops[it].priority }.ifEmpty { remaining.toList() }
            var best = pool.first()
            var bestScore = Double.POSITIVE_INFINITY
            for (i in pool) {
                val s = stops[i]
                val arrive = t + dur[prev][i + 1]
                val open = s.window?.first?.toDouble() ?: 0.0
                val close = s.window?.last?.toDouble() ?: Double.POSITIVE_INFINITY
                var score = maxOf(arrive, open) - t
                if (arrive > close) score += 1e5
                if (close.isFinite()) score += (close - arrive) * 1e-3
                if (score < bestScore) { bestScore = score; best = i }
            }
            val s = stops[best]
            t = maxOf(t + dur[prev][best + 1], s.window?.first?.toDouble() ?: 0.0) + s.serviceSec
            prev = best + 1
            order += best
            remaining -= best
        }
        return order
    }

    fun solveOrder(stops: List<Stop>, dur: Array<DoubleArray>): List<Int> {
        if (stops.size < 2) return stops.indices.toList()
        var order = construct(stops, dur)
        var best = evaluate(order, stops, dur).cost
        var improved = true
        var rounds = 0
        while (improved && rounds++ < 100) {
            improved = false
            for (i in 0 until order.size - 1) for (j in i + 1 until order.size) {
                val cand = order.subList(0, i) + order.subList(i, j + 1).reversed() + order.subList(j + 1, order.size)
                val c = evaluate(cand, stops, dur).cost
                if (c < best - 1e-9) { order = cand.toMutableList(); best = c; improved = true }
            }
            for (i in order.indices) for (j in order.indices) {
                if (i == j) continue
                val cand = order.toMutableList()
                cand.add(j, cand.removeAt(i))
                val c = evaluate(cand, stops, dur).cost
                if (c < best - 1e-9) { order = cand; best = c; improved = true }
            }
        }
        return order
    }

    /** Orders [stops] from [start], filling original/optimized index, ETA and lateness. */
    fun optimize(start: LatLng, stops: List<Stop>, provider: MatrixProvider): Result {
        val m = provider.matrix(listOf(start) + stops.map { it.access })
        val order = solveOrder(stops, m.durations)
        val ev = evaluate(order, stops, m.durations)
        val out = order.mapIndexed { k, idx ->
            stops[idx].copy(
                originalIndex = idx + 1,
                optimizedIndex = k + 1,
                etaSec = ev.etas[k].arrival.toInt(),
                lateSec = ev.etas[k].late.toInt(),
            )
        }
        return Result(out, ev.totalSec.toInt(), ev.etas.count { it.late > 0 })
    }
}
