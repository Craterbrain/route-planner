package app.routeplanner.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

private const val EARTH_M = 6_371_008.8

/** Great-circle distance in meters. */
fun haversine(a: LatLng, b: LatLng): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_M * asin(sqrt(h))
}

class Matrix(val durations: Array<DoubleArray>, val distances: Array<DoubleArray>)

/** Source of travel times between points. Index 0 is the depot/start. */
fun interface MatrixProvider {
    fun matrix(points: List<LatLng>): Matrix
}

/** Offline provider: straight line * detour factor at an average speed. */
class HaversineMatrix(private val detour: Double = 1.3, private val speedMps: Double = 11.0) : MatrixProvider {
    override fun matrix(points: List<LatLng>): Matrix {
        val n = points.size
        val dist = Array(n) { DoubleArray(n) }
        val dur = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0 until n) if (i != j) {
            val d = haversine(points[i], points[j]) * detour
            dist[i][j] = d
            dur[i][j] = d / speedMps
        }
        return Matrix(dur, dist)
    }
}
