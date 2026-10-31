package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val csv = """
        address,lat,lng,package,zone,priority,window_start,window_end,service_min,notes
        "100 Main St, Springfield",39.7990,-89.6440,PKG-001,Shelf A,,,,2,
        "22 Oak Ave, Springfield",39.8100,-89.6300,PKG-002,Bin 3,yes,,,2,Ring bell
        "22 Oak Ave, Springfield",39.8100,-89.6300,PKG-003,Bin 3,,,,2,
        "9 Elm Rd, Springfield",39.7800,-89.6700,PKG-004,Trunk Left,,09:00,11:00,3,Business hours
        "57 Pine Ln, Springfield",39.8300,-89.6000,PKG-005,Shelf B,,,,2,
        "310 River Dr, Springfield",39.7700,-89.6100,PKG-006,Front Shelf,,10:00,12:00,2,
    """.trimIndent()
    private val depot = LatLng(39.79, -89.65)

    @Test fun csvQuotesAndCrlf() {
        assertEquals(listOf(listOf("a", "b"), listOf("x, y", "say \"hi\"")), parseCsv("a,b\r\n\"x, y\",\"say \"\"hi\"\"\"\r\n"))
    }

    @Test fun haversineDegreeOfLatitude() {
        assertEquals(111195.0, haversine(LatLng(0.0, 0.0), LatLng(1.0, 0.0)), 200.0)
    }

    @Test fun manifestMergesAddresses() {
        val m = importManifest(csv, dayStartSec = 8 * 3600)
        assertTrue(m.errors.isEmpty())
        assertEquals(5, m.stops.size)
        assertEquals(6, m.packages.size)
        assertEquals(3600L..10800L, m.stops[2].window)
    }

    @Test fun manifestReportsMissingCoords() {
        assertEquals(1, importManifest("address,lat,lng\nfoo,,\n").errors.size)
    }

    @Test fun optimizerPriorityWindowsIndexes() {
        val r = Solver.optimize(depot, importManifest(csv, 8 * 3600).stops, HaversineMatrix())
        assertTrue(r.stops[0].priority)
        assertEquals(0, r.lateStops)
        assertEquals(listOf(1, 2, 3, 4, 5), r.stops.map { it.optimizedIndex })
    }

    @Test fun optimizerNoWorseThanManifestOrder() {
        val pts = (0 until 12).map { i ->
            Stop("s$i", "s$i", LatLng(40 + ((i * 7) % 12) * 0.01, -90 + ((i * 5) % 12) * 0.01), serviceSec = 0)
        }
        val r = Solver.optimize(depot, pts, HaversineMatrix())
        val m = HaversineMatrix().matrix(listOf(depot) + pts.map { it.access })
        val naive = Solver.evaluate(pts.indices.toList(), pts, m.durations).totalSec
        assertTrue(r.totalSec <= naive)
    }

    @Test fun deepLinks() {
        assertEquals("google.navigation:q=1.0,2.0&mode=d", navLink(NavApp.GOOGLE, LatLng(1.0, 2.0)))
        assertTrue(navLink(NavApp.WAZE, LatLng(1.0, 2.0)).contains("navigate=yes"))
    }

    @Test fun sessionDeliverPrevAutoNav() {
        val m = importManifest(csv)
        val s = RouteSession(m.stops, m.packages, autoNavigate = true)
        val first = s.current!!.id
        assertTrue(s.finder().isNotEmpty())
        assertTrue(s.deliver()!!.startsWith("google.navigation:"))
        assertNotEquals(first, s.current!!.id)
        s.prev()
        assertEquals(first, s.current!!.id)
        assertEquals(Status.PENDING, s.current!!.status)
        assertEquals(Status.PENDING, s.packages.first { it.stopId == first }.status)
    }

    @Test fun injectionKeepsCompleted() {
        val m = importManifest(csv)
        val s = RouteSession(m.stops, m.packages)
        s.deliver()
        val doneId = s.stops[0].id
        s.inject(Stop("new", "new", LatLng(39.8, -89.64)), HaversineMatrix(), depot)
        assertEquals(doneId, s.stops[0].id)
        assertEquals(Status.COMPLETED, s.stops[0].status)
        assertTrue(s.stops.any { it.id == "new" })
        assertEquals(Status.PENDING, s.current!!.status)
    }

    @Test fun jumpToMovesCursor() {
        val m = importManifest(csv)
        val s = RouteSession(m.stops, m.packages)
        s.jumpTo(m.stops[3].id)
        assertEquals(m.stops[3].id, s.current!!.id)
    }
}
