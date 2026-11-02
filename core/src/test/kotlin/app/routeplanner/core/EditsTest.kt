package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class EditsTest {
    private val depot = LatLng(40.0, -90.0)

    /** Stops on a line east of the depot, 0.01° apart, no service time. */
    private fun line(n: Int, f: (Int, Stop) -> Stop = { _, s -> s }) = (0 until n).map { i ->
        f(i, Stop("s$i", "$i Main St, Town", LatLng(40.0, -90.0 + (i + 1) * 0.01), serviceSec = 0))
    }

    private fun session(n: Int = 5, clock: Long = 1_000_000L) =
        RouteSession(Solver.schedule(depot, line(n), HaversineMatrix()).stops, now = { clock }, startMs = clock)

    private fun ids(s: RouteSession) = s.stops.map { it.id }

    @Test fun lastPlacementGoesLast() {
        val stops = line(5) { i, s -> if (i == 0) s.copy(placement = Placement.LAST) else s }
        val r = Solver.optimize(depot, stops, HaversineMatrix())
        assertEquals("s0", r.stops.last().id)
    }

    @Test fun firstBeatsLastAndAuto() {
        val stops = line(5) { i, s ->
            when (i) { 4 -> s.copy(placement = Placement.FIRST); 0 -> s.copy(placement = Placement.LAST); else -> s }
        }
        val r = Solver.optimize(depot, stops, HaversineMatrix())
        assertEquals("s4", r.stops.first().id)
        assertEquals("s0", r.stops.last().id)
    }

    @Test fun startOffsetShiftsEtasAndWindows() {
        val stops = line(2)
        val a = Solver.schedule(depot, stops, HaversineMatrix(), t0 = 0)
        val b = Solver.schedule(depot, stops, HaversineMatrix(), t0 = 3600)
        assertEquals(a.stops[1].etaSec + 3600, b.stops[1].etaSec)
        // A window that closed before t0 makes the stop late.
        val late = Solver.schedule(depot, listOf(stops[0].copy(window = 0L..600L)), HaversineMatrix(), t0 = 3600)
        assertEquals(1, late.lateStops)
    }

    @Test fun scheduleKeepsOrderAndSumsDistance() {
        val stops = line(4).reversed()
        val r = Solver.schedule(depot, stops, HaversineMatrix())
        assertEquals(stops.map { it.id }, r.stops.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), r.stops.map { it.optimizedIndex })
        assertTrue(r.stops.all { it.legMeters > 0 })
        assertEquals(r.stops.sumOf { it.legMeters }.toDouble(), r.totalMeters.toDouble(), 4.0)
    }

    @Test fun moveStopRenumbersAndKeepsCursor() {
        val s = session()
        s.jumpTo("s2")
        assertTrue(s.moveStop("s4", 0))
        assertEquals(listOf("s4", "s0", "s1", "s2", "s3"), ids(s))
        assertEquals(listOf(1, 2, 3, 4, 5), s.stops.map { it.optimizedIndex })
        assertEquals("s2", s.current!!.id)
        assertTrue(s.dirty)
    }

    @Test fun makeNextGoesAfterCurrent() {
        val s = session()
        s.start()
        assertEquals("s0", s.current!!.id)
        s.makeNext("s3")
        assertEquals(listOf("s0", "s3", "s1", "s2", "s4"), ids(s))
        assertEquals("s0", s.current!!.id)
        s.deliver()
        assertEquals("s3", s.current!!.id)
    }

    @Test fun makeNextIgnoresFinished() {
        val s = session()
        s.deliver()
        assertFalse(s.makeNext("s0"))
    }

    @Test fun reverseKeepsFinishedInPlace() {
        val s = session(4)
        s.deliver() // s0 done
        s.reverse()
        assertEquals(listOf("s0", "s3", "s2", "s1"), ids(s))
        assertEquals("s3", s.current!!.id)
    }

    @Test fun removeCurrentMovesToNextPending() {
        val s = session(4)
        s.start()
        s.deliver() // s0 done, current s1
        s.removeStop("s1")
        assertEquals(listOf("s0", "s2", "s3"), ids(s))
        assertEquals("s2", s.current!!.id)
        s.removeStop("s3"); s.removeStop("s2"); s.removeStop("s0")
        assertTrue(s.stops.isEmpty())
        assertNull(s.current)
    }

    @Test fun removeStopDropsPackages() {
        val s = RouteSession(line(2), listOf(Pkg("p0", "s0"), Pkg("p1", "s1")))
        s.removeStop("s0")
        assertEquals(listOf("p1"), s.packages.map { it.id })
    }

    @Test fun markOtherStopKeepsCursorAndUndo() {
        val s = session()
        s.start()
        assertNull(s.mark("s3", Status.COMPLETED))
        assertEquals("s0", s.current!!.id)
        assertEquals(Status.COMPLETED, s.stop("s3")!!.status)
        s.undo("s3")
        assertEquals(Status.PENDING, s.stop("s3")!!.status)
        assertNull(s.stop("s3")!!.completedAt)
    }

    @Test fun editMarksDirtyOnlyForRouteFields() {
        val s = session()
        s.edit("s1") { it.copy(notes = "gate 1234") }
        assertFalse(s.dirty)
        s.edit("s1") { it.copy(placement = Placement.LAST) }
        assertTrue(s.dirty)
        assertEquals("gate 1234", s.stop("s1")!!.notes)
    }

    @Test fun setAccessAndZone() {
        val s = RouteSession(line(1), listOf(Pkg("p0", "s0")))
        assertEquals(LatLng(1.0, 2.0), s.setAccess("s0", LatLng(1.0, 2.0))!!.access)
        s.setZone("p0", "  Back Left Shelf ")
        assertEquals("Back Left Shelf", s.packages[0].truckZone)
        s.setZone("p0", " ")
        assertNull(s.packages[0].truckZone)
    }

    @Test fun addStopAppendsAndMarksDirty() {
        val s = session(2)
        s.addStop(Stop("new", "new", LatLng(40.0, -89.9)), listOf(Pkg("pn", "new")))
        assertEquals("new", s.stops.last().id)
        assertEquals(3, s.stops.last().optimizedIndex)
        assertTrue(s.dirty)
        assertEquals(1, s.finder(s.stops.last()).size)
    }

    @Test fun addStopToFinishedRouteBecomesCurrent() {
        val s = session(1)
        s.deliver()
        s.addStop(Stop("new", "new", LatLng(40.0, -89.9)))
        assertEquals("new", s.current!!.id)
    }

    @Test fun applyPlanToleratesConcurrentChanges() {
        val s = session(4)
        val plan = s.planRemaining(HaversineMatrix(), depot, optimize = true)
        // Meanwhile the driver delivers s0 and removes s2.
        s.deliver()
        s.removeStop("s2")
        s.applyPlan(plan)
        assertEquals(listOf("s0", "s1", "s3"), ids(s))
        assertEquals(Status.COMPLETED, s.stops[0].status)
        assertEquals("s1", s.current!!.id)
        assertFalse(s.dirty)
    }

    @Test fun applyPlanKeepsEditsMadeMeanwhile() {
        val s = session(3)
        val plan = s.planRemaining(HaversineMatrix(), depot, optimize = false)
        s.edit("s1") { it.copy(notes = "dog") }
        s.applyPlan(plan)
        assertEquals("dog", s.stop("s1")!!.notes)
    }

    @Test fun planRemainingUsesRouteClock() {
        var clock = 0L
        val s = RouteSession(line(2), now = { clock }, startMs = 0L)
        clock = 600_000L // ten minutes in
        val plan = s.planRemaining(HaversineMatrix(), depot, optimize = false)
        assertTrue(plan.stops[0].etaSec >= 600)
    }

    @Test fun summaryAddsDriftAndCounts() {
        val stops = listOf(
            Stop("a", "a", depot, serviceSec = 60, etaSec = 100, legMeters = 1000, status = Status.COMPLETED),
            Stop("b", "b", depot, serviceSec = 60, etaSec = 400, legMeters = 2000, window = 0L..450L),
            Stop("c", "c", depot, serviceSec = 120, etaSec = 900, legMeters = 3000),
        )
        val onTime = RouteSummary.of(stops, nowSec = 300)
        assertEquals(0, onTime.driftSec)
        assertEquals(1020, onTime.finishSec)
        assertEquals(720, onTime.remainingSec)
        assertEquals(6000, onTime.totalMeters)
        assertEquals(5000, onTime.remainingMeters)
        assertEquals(1, onTime.delivered)
        assertEquals(2, onTime.pending)
        assertEquals(0, onTime.lateStops)
        val behind = RouteSummary.of(stops, nowSec = 500)
        assertEquals(100, behind.driftSec)
        assertEquals(1120, behind.finishSec)
        assertEquals(1, behind.lateStops) // b now projected at 500 > 450
        assertEquals(500, behind.projectedEta(stops[1]))
        assertEquals(100, behind.projectedEta(stops[0]))
    }

    @Test fun summaryOfFinishedRoute() {
        val s = RouteSummary.of(listOf(Stop("a", "a", depot, etaSec = 100, serviceSec = 20, status = Status.FAILED)), 999)
        assertEquals(0, s.remainingSec)
        assertEquals(120, s.finishSec)
        assertEquals(1, s.failed)
    }

    @Test fun formatters() {
        assertEquals("<1 min", formatDuration(30))
        assertEquals("45 min", formatDuration(45 * 60))
        assertEquals("2 h 05 min", formatDuration(2 * 3600 + 5 * 60))
        assertEquals("850 m", formatDistance(850, false))
        assertEquals("12.4 km", formatDistance(12_400, false))
        assertEquals("1.0 mi", formatDistance(1609, true))
        assertEquals("100 Main St" to "Springfield, IL", splitAddress("100 Main St, Springfield, IL"))
        assertEquals("Depot" to "", splitAddress(" Depot "))
    }

    @Test fun placementColumn() {
        assertEquals(Placement.FIRST, parsePlacement("Yes"))
        assertEquals(Placement.FIRST, parsePlacement("first"))
        assertEquals(Placement.LAST, parsePlacement(" LAST "))
        assertEquals(Placement.AUTO, parsePlacement(""))
        val m = importManifest("address,lat,lng,priority\na,1,1,last\nb,1,2,\n")
        assertEquals(Placement.LAST, m.stops[0].placement)
    }

    @Test fun applyPlanKeepCursor() {
        val s = session(4)
        s.start()
        s.jumpTo("s2")
        s.applyPlan(s.planRemaining(HaversineMatrix(), depot, optimize = false), keepCursor = true)
        assertEquals("s2", s.current!!.id)
        s.applyPlan(s.planRemaining(HaversineMatrix(), depot, optimize = false))
        assertEquals("s0", s.current!!.id)
    }

    @Test fun backDoesNotRevert() {
        val s = session(3)
        s.deliver()
        s.back()
        assertEquals("s0", s.current!!.id)
        assertEquals(Status.COMPLETED, s.current!!.status)
        s.back() // at the start: stays
        assertEquals(0, s.cursor)
    }

    @Test fun theRouteLineIsOnlyForAnOptimizedOrder() {
        val s = session(4)
        assertFalse("a fresh route has not been optimized", s.optimized)
        s.applyPlan(s.planRemaining(HaversineMatrix(), depot, optimize = true))
        assertTrue(s.optimized)
        s.addStop(Stop("late", "late", LatLng(40.0, -89.8)))        // any route-changing edit
        assertFalse(s.optimized)
        s.applyPlan(s.planRemaining(HaversineMatrix(), depot, optimize = false), keepCursor = true)   // re-timing only
        assertFalse("re-timing does not make the order a chosen one", s.optimized)
        s.applyPlan(s.planRemaining(HaversineMatrix(), depot, optimize = true))
        assertTrue(s.optimized)
    }
}
