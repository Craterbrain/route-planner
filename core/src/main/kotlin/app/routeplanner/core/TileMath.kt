package app.routeplanner.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** Axis-aligned lat/lng box. */
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** Grows the box by [fraction] of its size on every side (at least [minDeg] degrees), clamped to the map. */
    fun padded(fraction: Double, minDeg: Double = 0.01): Bounds {
        val dLat = max((north - south) * fraction, minDeg)
        val dLng = max((east - west) * fraction, minDeg)
        return Bounds(max(-85.0, south - dLat), max(-180.0, west - dLng), min(85.0, north + dLat), min(180.0, east + dLng))
    }

    companion object {
        fun around(points: List<LatLng>): Bounds? =
            if (points.isEmpty()) null
            else Bounds(points.minOf { it.lat }, points.minOf { it.lng }, points.maxOf { it.lat }, points.maxOf { it.lng })
    }
}

/** Slippy-map tile arithmetic, used to size an offline download before starting it. */
object TileMath {
    private fun xTile(lng: Double, z: Int): Int =
        floor((lng + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    private fun yTile(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** Tiles needed to cover [b] at one zoom level. */
    fun tilesAt(b: Bounds, z: Int): Long {
        val x0 = xTile(b.west, z); val x1 = xTile(b.east, z)
        val y0 = yTile(b.north, z); val y1 = yTile(b.south, z)
        return (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
    }

    /** Tiles needed to cover [b] for every zoom in [minZoom]..[maxZoom]. */
    fun tileCount(b: Bounds, minZoom: Int, maxZoom: Int): Long = (minZoom..maxZoom).sumOf { tilesAt(b, it) }

    /**
     * The deepest zoom (at most [ceiling]) whose pyramid from [minZoom] stays within [limit] tiles,
     * or [minZoom] if even that is over.
     */
    fun deepestZoom(b: Bounds, minZoom: Int, ceiling: Int, limit: Long): Int {
        var best = minZoom
        for (z in minZoom..ceiling) { if (tileCount(b, minZoom, z) <= limit) best = z else break }
        return best
    }

    /** Roughly how many km the box spans east-west at its middle latitude. */
    fun widthKm(b: Bounds): Double = (b.east - b.west) * 111.32 * cos(Math.toRadians((b.north + b.south) / 2))
    fun heightKm(b: Bounds): Double = (b.north - b.south) * 110.57

    @Suppress("unused") private fun tileLat(y: Int, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))
}
