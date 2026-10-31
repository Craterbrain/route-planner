package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class PhotonTest {
    private val sample = javaClass.getResource("/photon-sample.json")!!.readText()

    @Test fun parsesRecordedResponse() {
        val r = PhotonGeocoder.parse(sample)
        assertEquals(3, r.size)
        assertEquals("1223 East Main Street", r[0].line1)
        assertEquals("Canton, Georgia 30114", r[0].line2)
        assertEquals("1223 East Main Street, Canton, Georgia 30114", r[0].label)
        assertEquals(34.236086, r[0].point.lat, 1e-5)
        assertEquals(-84.476010, r[0].point.lng, 1e-5)
    }

    @Test fun formatsNamedPlaceAndSkipsBadFeatures() {
        val json = """{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"name":"Cumberland County Courthouse","street":"Main Street","housenumber":"2","city":"Crossville","state":"Tennessee"},"geometry":{"type":"Point","coordinates":[-85.02,35.95]}},
          {"type":"Feature","properties":{"name":"Crossville","state":"Tennessee"},"geometry":{"type":"Point","coordinates":[-85.03,35.94]}},
          {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[1.0,2.0]}},
          {"type":"Feature","properties":{"name":"no geometry"}}]}"""
        val r = PhotonGeocoder.parse(json)
        assertEquals(2, r.size)
        assertEquals("Cumberland County Courthouse, 2 Main Street", r[0].line1)
        assertEquals("Crossville, Tennessee", r[0].line2)
        assertEquals("Crossville", r[1].line1)
        assertEquals("Tennessee", r[1].line2)
    }

    @Test fun buildsUrlWithBiasAndHeaders() {
        var seenUrl = ""
        var seenUa = ""
        val g = PhotonGeocoder("https://geo.example.com/", { url, h -> seenUrl = url; seenUa = h["User-Agent"].orEmpty(); sample },
            userAgent = "test-agent", bias = { LatLng(35.95, -85.03) })
        val r = g.search("1223 main", 3)
        assertEquals(3, r.size)
        assertEquals("https://geo.example.com/api/?q=1223+main&limit=3&lang=en&lat=35.95&lon=-85.03", seenUrl)
        assertEquals("test-agent", seenUa)
    }

    @Test fun blankUrlUsesPublicInstance() {
        assertTrue(PhotonGeocoder("  ").searchUrl("abc", 5, null).startsWith("https://photon.komoot.io/api/?q=abc"))
    }

    @Test fun shortQueriesNeverHitTheServer() {
        var calls = 0
        val g = PhotonGeocoder(http = { _, _ -> calls++; sample })
        assertTrue(g.search("12").isEmpty())
        assertEquals(0, calls)
    }

    @Test fun networkErrorsPropagateFromSearchButNotGeocode() {
        val g = PhotonGeocoder(http = { _, _ -> throw java.io.IOException("unreachable") })
        assertThrows(java.io.IOException::class.java) { g.search("1223 main") }
        assertNull(g.geocode("1223 main"))
    }

    @Test fun reverseTakesFirstFeature() {
        var url = ""
        val g = PhotonGeocoder(http = { u, _ -> url = u; sample })
        assertEquals("1223 East Main Street", g.reverse(LatLng(1.0, 2.0))!!.line1)
        assertTrue(url.startsWith("https://photon.komoot.io/reverse?lat=1.0&lon=2.0"))
    }

    @Test fun chainFallsThrough() {
        val chain = ChainGeocoder({ null }, { throw IllegalStateException("boom") }, { LatLng(1.0, 1.0) })
        assertEquals(LatLng(1.0, 1.0), chain.geocode("x"))
    }

    @Test fun feetForShortImperialDistances() {
        assertEquals("130 ft", formatDistance(40, true))
        assertEquals("0.2 mi", formatDistance(320, true))
    }
}
