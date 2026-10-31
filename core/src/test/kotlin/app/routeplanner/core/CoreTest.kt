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

    @Test fun jsonParses() {
        val j = Json.parse("""{"a":[1,2.5,-3e1],"b":{"c":"x\ny\u0041"},"d":null,"e":true}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -30.0), j["a"])
        assertEquals("x\nyA", (j["b"] as Map<*, *>)["c"])
        assertNull(j["d"]); assertEquals(true, j["e"])
    }

    @Test fun valhallaMatrixUsesResponseAndFallsBack() {
        val pts = listOf(LatLng(39.0, -89.0), LatLng(39.1, -89.0), LatLng(39.2, -89.0))
        val ok = Http { _, _ ->
            """{"sources_to_targets":[
              [{"from_index":0,"to_index":0,"distance":0,"time":0},{"from_index":0,"to_index":1,"distance":2.5,"time":300},{"from_index":0,"to_index":2,"distance":null,"time":null}],
              [{"from_index":1,"to_index":0,"distance":2.6,"time":310},{"from_index":1,"to_index":1,"distance":0,"time":0},{"from_index":1,"to_index":2,"distance":2.5,"time":290}],
              [{"from_index":2,"to_index":0,"distance":5.0,"time":600},{"from_index":2,"to_index":1,"distance":2.4,"time":280},{"from_index":2,"to_index":2,"distance":0,"time":0}]]}"""
        }
        val v = ValhallaMatrix("http://x", ok)
        val m = v.matrix(pts)
        assertEquals(300.0, m.durations[0][1], 0.0)
        assertEquals(2500.0, m.distances[0][1], 0.0)
        assertEquals(HaversineMatrix().matrix(pts).durations[0][2], m.durations[0][2], 0.0) // null cell -> estimate
        assertFalse(v.degraded)
        val bad = ValhallaMatrix("http://x", Http { _, _ -> throw java.io.IOException("down") })
        assertEquals(HaversineMatrix().matrix(pts).durations[1][2], bad.matrix(pts).durations[1][2], 0.0)
        assertTrue(bad.degraded)
    }

    @Test fun valhallaTilesLargeInputs() {
        var calls = 0
        val pts = (0 until 5).map { LatLng(39.0 + it * 0.01, -89.0) }
        ValhallaMatrix("http://x", Http { _, _ -> calls++; """{"sources_to_targets":[]}""" }, block = 2).matrix(pts)
        assertEquals(9, calls) // ceil(5/2)^2
    }

    @Test fun manifestGeocodesAndAppliesMemory() {
        val csv2 = "address,package\n\"1 A St\",P1\n\"2  B St\",P2\n\"nowhere\",P3\n"
        val mem = InMemoryAddressMemory().also { it.remember("2 b st", LatLng(5.0, 6.0)) }
        val m = importManifest(csv2, geocoder = { a -> if (a == "nowhere") null else LatLng(1.0, 2.0) }, memory = mem)
        assertEquals(2, m.stops.size)
        assertEquals(1, m.errors.size)
        assertEquals(LatLng(1.0, 2.0), m.stops[0].access)
        assertEquals(LatLng(1.0, 2.0), m.stops[1].target)
        assertEquals(LatLng(5.0, 6.0), m.stops[1].access)
    }

    @Test fun deliverStoresProof() {
        val m = importManifest(csv)
        val s = RouteSession(m.stops, m.packages)
        val id = s.current!!.id
        s.deliver(note = "Left behind back porch pot", photo = "/p/1.jpg")
        val d = s.stops.first { it.id == id }
        assertEquals("Left behind back porch pot", d.proofNote)
        assertEquals("/p/1.jpg", d.proofPhoto)
        s.jumpTo(id); s.prev(); s.jumpTo(id)
    }
}
