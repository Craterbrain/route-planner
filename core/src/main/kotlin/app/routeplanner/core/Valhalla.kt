package app.routeplanner.core

import java.net.HttpURLConnection
import java.net.URL

fun interface Http {
    /** POSTs JSON [body] to [url] and returns the response body; throws on failure. */
    fun post(url: String, body: String): String
}

object UrlHttp : Http {
    override fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Road-network matrix from a (self-hosted) Valhalla `/sources_to_targets`.
 * Large inputs are tiled into blocks of at most [block] x [block]; any pair
 * Valhalla can't route, or a failed request, falls back to [fallback].
 */
class ValhallaMatrix(
    private val baseUrl: String,
    private val http: Http = UrlHttp,
    private val costing: String = "auto",
    private val block: Int = 50,
    private val fallback: MatrixProvider = HaversineMatrix(),
) : MatrixProvider {

    /** True once any request failed during the last [matrix] call (UI can warn "estimated times"). */
    var degraded = false
        private set

    override fun matrix(points: List<LatLng>): Matrix {
        degraded = false
        val est = fallback.matrix(points)
        val n = points.size
        val dist = Array(n) { est.distances[it].copyOf() }
        val dur = Array(n) { est.durations[it].copyOf() }
        val url = baseUrl.trimEnd('/') + "/sources_to_targets"
        for (r0 in 0 until n step block) for (c0 in 0 until n step block) {
            val rows = r0 until minOf(n, r0 + block)
            val cols = c0 until minOf(n, c0 + block)
            try {
                val resp = Json.parse(http.post(url, request(points, rows, cols))) as Map<*, *>
                val grid = resp["sources_to_targets"] as List<*>
                for ((ri, rowAny) in grid.withIndex()) for (cell in rowAny as List<*>) {
                    cell as Map<*, *>
                    val t = cell["time"] as? Double ?: continue
                    val d = cell["distance"] as? Double ?: continue
                    val i = rows.first + ri
                    val j = cols.first + (cell["to_index"] as Double).toInt()
                    if (i != j) { dur[i][j] = t; dist[i][j] = d * 1000 } // Valhalla reports km
                }
            } catch (e: Exception) {
                degraded = true
            }
        }
        return Matrix(dur, dist)
    }

    private fun request(p: List<LatLng>, rows: IntRange, cols: IntRange): String {
        fun loc(i: Int) = """{"lat":${p[i].lat},"lon":${p[i].lng}}"""
        return """{"sources":[${rows.joinToString(",") { loc(it) }}],"targets":[${cols.joinToString(",") { loc(it) }}],"costing":"$costing"}"""
    }
}
