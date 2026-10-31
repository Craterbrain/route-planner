package app.routeplanner

import android.content.Context
import android.location.Geocoder as AndroidGeocoder
import android.util.Log
import app.routeplanner.core.*

/**
 * Address lookup chain: the platform geocoder when the phone has one and it
 * answers, otherwise Photon (OSM, online, no key; URL configurable in
 * Settings). All calls block; run them off the UI thread.
 */
class AddressSearch(ctx: Context, prefs: Prefs, bias: () -> LatLng? = { null }) {
    private val app = ctx.applicationContext
    private val platform = PlatformGeocoder(app)
    private val geocoderBase = prefs.geocoderUrl
    val photon = PhotonGeocoder(prefs.geocoderUrl, userAgent = NetStatus.userAgent(), bias = bias)

    sealed class Outcome {
        class Found(val items: List<Suggestion>, val source: String) : Outcome()
        object Empty : Outcome()
        class Failed(val message: String) : Outcome()
    }

    /** Suggestions for the search bar. */
    fun suggest(query: String, limit: Int = 5): Outcome {
        if (AndroidGeocoder.isPresent()) {
            val hits = runCatching { platform.candidates(query, limit) }.getOrDefault(emptyList())
            if (hits.isNotEmpty()) return Outcome.Found(hits, "device geocoder")
        }
        if (query.trim().length < PhotonGeocoder.MIN_CHARS) return Outcome.Empty
        return try {
            val hits = photon.search(query, limit)
            if (hits.isEmpty()) Outcome.Empty else Outcome.Found(hits, "Photon")
        } catch (e: Exception) {
            Log.w(NetStatus.TAG, "address search failed: $e")
            Outcome.Failed(failureMessage(e))
        }
    }

    private fun failureMessage(e: Exception): String = when {
        NetStatus.systemSaysOffline(app) -> "No network access: address search needs the internet."
        e is HttpStatusException -> "Address search error (HTTP ${e.code})."
        else -> "Couldn't reach address search."
    }

    /** Geocoder for spreadsheet import: platform first, then Photon. */
    fun geocoder(): Geocoder = ChainGeocoder(platform, photon)

    /** Address at [p] for long-press, or null. */
    fun reverse(p: LatLng): String? =
        platform.reverse(p) ?: runCatching { photon.reverse(p)?.label }.getOrNull()

    /** Kick a shared reachability check (map chip / permissions sheet use the result). */
    fun recheckNetwork() = NetStatus.check(app, geocoderBase)
}
