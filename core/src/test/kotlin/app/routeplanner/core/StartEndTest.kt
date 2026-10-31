package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class StartEndTest {
    private val depot = LatLng(40.0, -90.0)
    private fun line(n: Int) = (0 until n).map { i -> Stop("s$i", "s$i", LatLng(40.0, -90.0 + (i + 1) * 0.01), serviceSec = 0) }

    @Test fun roundTripAddsReturnLeg() {
        val open = Solver.optimize(depot, line(3), HaversineMatrix())
        val round = Solver.optimize(depot, line(3), HaversineMatrix(), end = depot)
        assertEquals(0, open.endLegSec)
        assertTrue(round.endLegSec > 0)
        assertTrue(round.endLegMeters > 0)
        assertEquals((round.stops.sumOf { it.legMeters } + round.endLegMeters).toDouble(), round.totalMeters.toDouble(), 2.0)
        assertTrue(round.totalSec >= open.totalSec + round.endLegSec - 2)
    }

    @Test fun endPointChangesOrder() {
        // Start in the middle; stops west and east. Ending far east means visit west first.
        val mid = LatLng(40.0, -90.0)
        val stops = listOf(
            Stop("w", "w", LatLng(40.0, -90.02), serviceSec = 0),
            Stop("e", "e", LatLng(40.0, -89.98), serviceSec = 0),
        )
        val r = Solver.optimize(mid, stops, HaversineMatrix(), end = LatLng(40.0, -89.90))
        assertEquals(listOf("w", "e"), r.stops.map { it.id })
        val r2 = Solver.optimize(mid, stops, HaversineMatrix(), end = LatLng(40.0, -90.10))
        assertEquals(listOf("e", "w"), r2.stops.map { it.id })
    }

    @Test fun scheduleWithEndKeepsOrder() {
        val r = Solver.schedule(depot, line(3).reversed(), HaversineMatrix(), end = depot)
        assertEquals(listOf("s2", "s1", "s0"), r.stops.map { it.id })
        assertTrue(r.endLegSec > 0)
    }

    @Test fun sessionEndPointAndOrigin() {
        val s = RouteSession(line(3), now = { 0L }, startMs = 0L)
        assertNull(s.planOrigin(null))
        assertEquals(LatLng(1.0, 1.0), s.planOrigin(LatLng(1.0, 1.0))) // no start yet: fall back to the driver
        s.start = RouteStart(depot, "Depot")
        assertEquals(depot, s.planOrigin(LatLng(1.0, 1.0)))          // planning: always the start
        assertNull(s.endPoint())
        s.end = RouteEnd(EndMode.ROUND_TRIP)
        assertEquals(depot, s.endPoint())
        s.end = RouteEnd(EndMode.ADDRESS, LatLng(2.0, 2.0), "Home")
        assertEquals(LatLng(2.0, 2.0), s.endPoint())
        s.start()
        assertEquals(depot, s.planOrigin(LatLng(1.0, 1.0)))          // started, nothing done: still the start
        s.deliver()
        assertEquals(LatLng(1.0, 1.0), s.planOrigin(LatLng(1.0, 1.0)))
        assertEquals(s.stops[0].access, s.planOrigin(null))          // no fix: last finished stop
    }

    @Test fun planRemainingUsesEndAndSummaryIncludesIt() {
        val s = RouteSession(line(3), now = { 0L }, startMs = 0L)
        s.start = RouteStart(depot, "Depot")
        s.end = RouteEnd(EndMode.ROUND_TRIP)
        s.applyPlan(s.planRemaining(HaversineMatrix(), s.planOrigin(null)!!))
        assertTrue(s.endLegSec > 0)
        val sum = s.summary(0L)
        val last = s.stops.last()
        assertEquals(last.etaSec + last.serviceSec + s.endLegSec, sum.finishSec)
        assertEquals(s.stops.sumOf { it.legMeters } + s.endLegMeters, sum.totalMeters)
        assertEquals(sum.totalMeters, sum.remainingMeters)
    }

    @Test fun summaryWithoutPendingDropsRemainingEndLeg() {
        val st = listOf(Stop("a", "a", depot, etaSec = 10, legMeters = 100, status = Status.COMPLETED))
        val sum = RouteSummary.of(st, 50, endLegSec = 30, endLegMeters = 500)
        assertEquals(0, sum.remainingMeters)
        assertEquals(600, sum.totalMeters)
    }
}
