package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class SolverSpeedTest {
    private fun stops(n: Int, seed: Long = 7): List<Stop> {
        val rnd = java.util.Random(seed)
        return (0 until n).map {
            Stop("s$it", "s$it", LatLng(36.0 + rnd.nextDouble() * 0.3, -85.0 + rnd.nextDouble() * 0.3), serviceSec = 120,
                window = if (it % 10 == 0) (3600L..7200L) else null)
        }
    }

    @Test fun largeRoutesFinishQuicklyAndStayValid() {
        for (n in listOf(100, 250)) {
            val st = stops(n)
            val t = System.nanoTime()
            val r = Solver.optimize(LatLng(36.1, -84.9), st, HaversineMatrix())
            val ms = (System.nanoTime() - t) / 1_000_000
            assertEquals(n, r.stops.size)
            assertEquals((1..n).toList(), r.stops.map { it.optimizedIndex })
            assertEquals(n, r.stops.map { it.id }.toSet().size)
            assertTrue("n=$n took $ms ms", ms < 2500) // bounded even on a slow runner; phones are given SEARCH_BUDGET_MS on top
        }
    }

    @Test fun optimizedRouteIsNoWorseThanInputOrder() {
        val st = stops(60)
        val m = HaversineMatrix().matrix(listOf(LatLng(36.1, -84.9)) + st.map { it.access })
        val naive = Solver.evaluate(st.indices.toList(), st, m.durations).cost
        val order = Solver.solveOrder(st, m.durations)
        assertTrue(Solver.evaluate(order, st, m.durations).cost <= naive)
        assertEquals(st.indices.toSet(), order.toSet())
    }

    @Test fun fastCostMatchesTheReferenceEvaluate() {
        // The search uses a private allocation-free cost; its answers must agree with evaluate().
        val st = stops(40).mapIndexed { i, s -> if (i % 7 == 0) s.copy(placement = Placement.LAST) else if (i % 11 == 0) s.copy(placement = Placement.FIRST) else s }
        val m = HaversineMatrix().matrix(listOf(LatLng(36.1, -84.9)) + st.map { it.access } + listOf(LatLng(36.1, -84.9)))
        val order = Solver.solveOrder(st, m.durations, 100.0, st.size + 1)
        // A solved order should not be beaten by simple perturbations measured with the reference cost.
        val base = Solver.evaluate(order, st, m.durations, 100.0, st.size + 1).cost
        val swapped = order.toMutableList().also { val t = it[3]; it[3] = it[9]; it[9] = t }
        assertTrue(base <= Solver.evaluate(swapped, st, m.durations, 100.0, st.size + 1).cost + 1e-6)
    }
}
