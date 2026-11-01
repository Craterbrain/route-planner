package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class RoadPathTest {
    @Test fun googlesPolylineExampleDecodes() {
        // The worked example from Google's polyline documentation (precision 5).
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-9); assertEquals(-120.2, pts[0].lng, 1e-9)
        assertEquals(40.7, pts[1].lat, 1e-9); assertEquals(-120.95, pts[1].lng, 1e-9)
        assertEquals(43.252, pts[2].lat, 1e-9); assertEquals(-126.453, pts[2].lng, 1e-9)
    }

    @Test fun encodeDecodeRoundTripAtPrecisionSix() {
        val pts = listOf(LatLng(35.909184, -84.898383), LatLng(35.90921, -84.89801), LatLng(36.0, -85.0), LatLng(-12.345678, 123.456789))
        val back = Polyline.decode(Polyline.encode(pts))
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) -> assertEquals(a.lat, b.lat, 1e-6); assertEquals(a.lng, b.lng, 1e-6) }
        assertTrue(runCatching { Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq") }.isFailure) // truncated
    }

    /** A fake Valhalla: every leg is a bent line through a midpoint, so road legs are recognisable. */
    private class Fake(val failWhen: (Int) -> Boolean = { false }) : Http {
        val sizes = ArrayList<Int>()
        override fun post(url: String, body: String): String {
            assertTrue(url.endsWith("/route"))
            val pts = Regex("""\{"lat":([-0-9.]+),"lon":([-0-9.]+)""").findAll(body).map { LatLng(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }.toList()
            sizes += pts.size
            if (failWhen(pts.size)) throw java.io.IOException("limit")
            val legs = pts.zipWithNext().joinToString(",") { (a, b) ->
                val mid = LatLng((a.lat + b.lat) / 2 + 0.001, (a.lng + b.lng) / 2)
                "{\"shape\":\"${Polyline.encode(listOf(a, mid, b))}\"}"
            }
            return "{\"trip\":{\"legs\":[$legs]}}"
        }
    }

    private fun pts(n: Int) = (0 until n).map { LatLng(36.0 + it * 0.01, -85.0 + (it % 3) * 0.01) }

    @Test fun smallRouteIsOneRequestAndFollowsTheRoadLegs() {
        val f = Fake()
        val r = ValhallaRoutes("http://x", f).path(pts(4))!!
        assertEquals(listOf(4), f.sizes)
        assertEquals(3, r.roadLegs); assertEquals(3, r.legs)
        assertEquals(1 + 3 * 2, r.path.size) // start + (mid, end) per leg, joints not repeated
        assertEquals(pts(4).first().lat, r.path.first().lat, 1e-6); assertEquals(pts(4).last().lat, r.path.last().lat, 1e-6)
    }

    @Test fun longRoutesAreSentInOverlappingWindows() {
        val f = Fake()
        val p = pts(40)
        val r = ValhallaRoutes("http://x", f, chunk = 15).path(p)!!
        assertEquals(listOf(15, 15, 12), f.sizes) // windows share their end/start point: 14 + 14 + 11 legs
        assertEquals(39, r.legs); assertEquals(39, r.roadLegs)
        assertEquals(1 + 39 * 2, r.path.size)
    }

    @Test fun aFailedWindowIsRetriedLegByLegAndBadLegsFallBackToStraightLines() {
        // Windows of more than 2 points fail; one particular leg fails even alone (a point not near any road).
        val bad = pts(8)[3]
        val f = object : Http {
            val inner = Fake { it > 2 }
            override fun post(url: String, body: String): String {
                if (body.contains("\"lat\":${bad.lat},")) throw java.io.IOException("no edges near location")
                return inner.post(url, body)
            }
        }
        val r = ValhallaRoutes("http://x", f).path(pts(8))!!
        assertEquals(7, r.legs)
        // the two legs touching the bad point are straight lines; the other five follow roads
        assertEquals(5, r.roadLegs)
        assertEquals(LatLng(bad.lat, bad.lng), r.path.first { it == bad })
    }

    @Test fun nothingToRouteWithFewerThanTwoPoints() {
        assertNull(ValhallaRoutes("http://x", Fake()).path(emptyList()))
        assertNull(ValhallaRoutes("http://x", Fake()).path(pts(1)))
    }

    @Test fun aRealValhallaResponseDecodesToARoadPathThroughTheStops() {
        // Recorded from Valhalla 3.9.1 on the Tennessee tiles: three points in Cumberland County, two legs.
        val body = javaClass.getResourceAsStream("/valhalla-route-sample.json")!!.bufferedReader().readText()
        val a = LatLng(35.9606, -85.0302); val b = LatLng(35.9092, -84.8984); val c = LatLng(35.9310, -85.0100)
        val r = ValhallaRoutes("on-device", Http { _, _ -> body }).path(listOf(a, b, c))!!
        assertEquals(2, r.legs); assertEquals(2, r.roadLegs)
        assertTrue("many road points, not a straight line", r.path.size > 200)
        assertTrue(r.path.all { it.lat in 35.8..36.1 && it.lng in -85.2..-84.8 })
        // the path starts at (near) the first stop and ends near the last: Valhalla snaps points to the road
        assertTrue(haversine(r.path.first(), a) < 300); assertTrue(haversine(r.path.last(), c) < 300)
        // and passes close to the middle stop
        assertTrue(r.path.minOf { haversine(it, b) } < 300)
    }
}
