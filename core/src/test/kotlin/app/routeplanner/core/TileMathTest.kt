package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class TileMathTest {
    @Test fun wholeWorldIsOneTileAtZoomZero() {
        assertEquals(1L, TileMath.tilesAt(Bounds(-80.0, -179.0, 80.0, 179.0), 0))
        assertEquals(4L, TileMath.tilesAt(Bounds(-80.0, -179.0, 80.0, 179.0), 1))
    }

    @Test fun smallBoxIsAFewTilesAndGrowsRoughlyFourfoldPerZoom() {
        val crossville = Bounds(35.90, -85.00, 36.00, -84.85)
        val z12 = TileMath.tilesAt(crossville, 12)
        val z14 = TileMath.tilesAt(crossville, 14)
        assertTrue("z12=$z12", z12 in 1..12)
        assertTrue("z14=$z14", z14 in z12 * 8..z12 * 24)
    }

    @Test fun tileCountSumsZooms() {
        val b = Bounds(35.90, -85.00, 36.00, -84.85)
        assertEquals((8..11).sumOf { TileMath.tilesAt(b, it) }, TileMath.tileCount(b, 8, 11))
    }

    @Test fun deepestZoomStaysUnderTheLimit() {
        val county = Bounds(35.75, -85.2, 36.15, -84.6)
        val z = TileMath.deepestZoom(county, 8, 14, 3000)
        assertTrue(TileMath.tileCount(county, 8, z) <= 3000)
        if (z < 14) assertTrue(TileMath.tileCount(county, 8, z + 1) > 3000)
        // A tiny area reaches the ceiling; a huge one falls back to the minimum.
        assertEquals(14, TileMath.deepestZoom(Bounds(35.9, -85.0, 35.91, -84.99), 8, 14, 3000))
        assertEquals(8, TileMath.deepestZoom(Bounds(25.0, -125.0, 49.0, -67.0), 8, 14, 3000))
    }

    @Test fun paddingGrowsAndClamps() {
        val b = Bounds(10.0, 10.0, 11.0, 12.0).padded(0.1)
        assertEquals(9.9, b.south, 1e-9); assertEquals(9.8, b.west, 1e-9)
        val tiny = Bounds(10.0, 10.0, 10.0, 10.0).padded(0.1, 0.01)
        assertEquals(10.01, tiny.north, 1e-9)
        assertEquals(85.0, Bounds(84.99, 0.0, 85.0, 1.0).padded(1.0).north, 1e-9)
    }

    @Test fun aroundPoints() {
        assertNull(Bounds.around(emptyList()))
        assertEquals(Bounds(1.0, 2.0, 3.0, 4.0), Bounds.around(listOf(LatLng(1.0, 4.0), LatLng(3.0, 2.0))))
    }
}
