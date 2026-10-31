package app.routeplanner.core

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** HTTP GET transport (fake in tests). Returns the body; throws on network errors or non-2xx. */
fun interface HttpGet {
    fun get(url: String, headers: Map<String, String>): String
}

/** Thrown when the server answered with a non-2xx status. */
class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")

object UrlGet : HttpGet {
    override fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 10_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode !in 200..299) throw HttpStatusException(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** A search-bar suggestion: display label (one or two lines) and position. */
data class Suggestion(val label: String, val point: LatLng, val line1: String = label, val line2: String = "")

/**
 * Photon (https://photon.komoot.io, OSM data, no key) address search. Needs
 * network; typed text is sent to the Photon server. [baseUrl] can point at a
 * self-hosted instance. [bias] (e.g. last known location) ranks nearby
 * results first.
 */
class PhotonGeocoder(
    baseUrl: String = DEFAULT_URL,
    private val http: HttpGet = UrlGet,
    private val userAgent: String = "app.routeplanner (Android delivery route planner)",
    private val lang: String = "en",
    private val bias: () -> LatLng? = { null },
) : Geocoder {
    private val base = baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_URL }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val headers get() = mapOf("User-Agent" to userAgent, "Accept" to "application/json")

    /** Search URL for [query] (exposed for tests). */
    fun searchUrl(query: String, limit: Int, near: LatLng? = bias()): String =
        "$base/api/?q=${enc(query.trim())}&limit=$limit&lang=$lang" +
            (near?.let { "&lat=${it.lat}&lon=${it.lng}" } ?: "")

    /** Up to [limit] matches; empty list = no matches. Throws on network/HTTP failure. */
    fun search(query: String, limit: Int = 5, near: LatLng? = bias()): List<Suggestion> {
        if (query.trim().length < MIN_CHARS) return emptyList()
        return parse(http.get(searchUrl(query, limit, near), headers))
    }

    /** Nearest address to [p], or null. Throws on network/HTTP failure. */
    fun reverse(p: LatLng): Suggestion? =
        parse(http.get("$base/reverse?lat=${p.lat}&lon=${p.lng}&limit=1&lang=$lang", headers)).firstOrNull()

    override fun geocode(address: String): LatLng? = runCatching { search(address, 1).firstOrNull()?.point }.getOrNull()

    companion object {
        const val DEFAULT_URL = "https://photon.komoot.io"
        /** Fewer characters than this never hit the server (fair use of the public instance). */
        const val MIN_CHARS = 3

        /** Parses a Photon GeoJSON FeatureCollection into suggestions. */
        fun parse(json: String): List<Suggestion> {
            val root = Json.parse(json) as? Map<*, *> ?: return emptyList()
            val features = root["features"] as? List<*> ?: return emptyList()
            return features.mapNotNull { f ->
                f as? Map<*, *> ?: return@mapNotNull null
                val coords = ((f["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*>) ?: return@mapNotNull null
                val lng = coords.getOrNull(0) as? Double ?: return@mapNotNull null
                val lat = coords.getOrNull(1) as? Double ?: return@mapNotNull null
                val p = f["properties"] as? Map<*, *> ?: emptyMap<String, Any?>()
                val (l1, l2) = format(p)
                if (l1.isEmpty()) return@mapNotNull null
                Suggestion(listOf(l1, l2).filter { it.isNotEmpty() }.joinToString(", "), LatLng(lat, lng), l1, l2)
            }
        }

        /** ("8681 US 70 E", "Crab Orchard, Tennessee 38555"); a named place leads with its name. */
        fun format(p: Map<*, *>): Pair<String, String> {
            fun s(k: String) = (p[k] as? String)?.trim().orEmpty()
            val streetLine = listOf(s("housenumber"), s("street")).filter { it.isNotEmpty() }.joinToString(" ")
            val name = s("name")
            val line1 = when {
                name.isNotEmpty() && streetLine.isNotEmpty() && name != s("street") -> "$name, $streetLine"
                streetLine.isNotEmpty() -> streetLine
                else -> name
            }
            val place = s("city").ifEmpty { s("town") }.ifEmpty { s("village") }.ifEmpty { s("district") }.ifEmpty { s("county") }
            val region = listOf(s("state"), s("postcode")).filter { it.isNotEmpty() }.joinToString(" ")
            val line2 = listOf(place, region).filter { it.isNotEmpty() && it != line1 }.joinToString(", ")
            return line1 to line2
        }
    }
}

/** Tries each geocoder in turn; the first non-null answer wins (e.g. platform, then Photon). */
class ChainGeocoder(private vararg val chain: Geocoder) : Geocoder {
    override fun geocode(address: String): LatLng? =
        chain.firstNotNullOfOrNull { g -> runCatching { g.geocode(address) }.getOrNull() }
}
