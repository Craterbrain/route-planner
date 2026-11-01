package app.routeplanner.core

/** Google-style encoded polylines, as Valhalla returns them (6 decimal digits). */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>()
        var i = 0; var lat = 0L; var lng = 0L
        fun next(): Long {
            var shift = 0; var result = 0L
            while (true) {
                if (i >= encoded.length) throw IllegalArgumentException("truncated polyline")
                val b = encoded[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < encoded.length) {
            lat += next(); lng += next()
            out += LatLng(lat / factor, lng / factor)
        }
        return out
    }

    /** Inverse of [decode]; used by tests and fixtures. */
    fun encode(points: List<LatLng>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pLat = 0L; var pLng = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x.toInt() + 63).toChar())
        }
        for (p in points) {
            val la = Math.round(p.lat * factor); val ln = Math.round(p.lng * factor)
            put(la - pLat); put(ln - pLng); pLat = la; pLng = ln
        }
        return sb.toString()
    }
}

/**
 * The roads a route follows, from a Valhalla `/route` (a server, or the on-device engine through [Http]).
 * Stops are sent in windows of [chunk] points (Valhalla caps locations per request); if a window fails, each of its
 * legs is asked for on its own, and a leg that still fails is drawn as a straight line so the path is never broken.
 */
class ValhallaRoutes(
    private val baseUrl: String,
    private val http: Http = UrlHttp,
    private val costing: String = "auto",
    private val chunk: Int = 15,
) {
    /** [path] runs through every point in order; [roadLegs] of [legs] legs followed roads (the rest are straight lines). */
    class Result(val path: List<LatLng>, val roadLegs: Int, val legs: Int)

    fun path(points: List<LatLng>): Result? {
        if (points.size < 2) return null
        val legPaths = arrayOfNulls<List<LatLng>>(points.size - 1)
        var from = 0
        while (from < points.size - 1) {
            val to = minOf(points.size - 1, from + chunk - 1)
            val window = points.subList(from, to + 1)
            val legs = runCatching { request(window) }.getOrNull()
            if (legs != null && legs.size == window.size - 1) legs.forEachIndexed { k, l -> legPaths[from + k] = l }
            else for (k in from until to) legPaths[k] = runCatching { request(points.subList(k, k + 2)) }.getOrNull()?.singleOrNull()
            from = to
        }
        val out = ArrayList<LatLng>()
        var road = 0
        for ((k, lp) in legPaths.withIndex()) {
            val seg = lp?.takeIf { it.size >= 2 }?.also { road++ } ?: listOf(points[k], points[k + 1])
            // Consecutive legs share their joint: don't repeat it.
            if (out.isNotEmpty() && out.last() == seg.first()) out.addAll(seg.drop(1)) else out.addAll(seg)
        }
        return Result(out, road, legPaths.size)
    }

    /** One request: the path of each leg between consecutive [pts], or throws. */
    private fun request(pts: List<LatLng>): List<List<LatLng>> {
        val locs = pts.joinToString(",") { """{"lat":${it.lat},"lon":${it.lng},"type":"break"}""" }
        val body = """{"locations":[$locs],"costing":"$costing","directions_type":"none"}"""
        val root = Json.parse(http.post(baseUrl.trimEnd('/') + "/route", body)) as Map<*, *>
        val legs = (root["trip"] as Map<*, *>)["legs"] as List<*>
        return legs.map { Polyline.decode((it as Map<*, *>)["shape"] as String) }
    }
}
