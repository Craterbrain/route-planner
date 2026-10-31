package app.routeplanner

import android.content.Context
import android.location.Geocoder as AndroidGeocoder
import app.routeplanner.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Small settings + per-address memory store (SharedPreferences, no DB yet). */
class Prefs(ctx: Context) {
    companion object { const val RECENT_MAX = 50 }

    private val sp = ctx.applicationContext.getSharedPreferences("route-planner", Context.MODE_PRIVATE)

    /** Base URL of a Valhalla server, e.g. http://192.168.1.10:8002. Blank = offline estimates. */
    var valhallaUrl: String
        get() = sp.getString("valhallaUrl", "") ?: ""
        set(v) = sp.edit().putString("valhallaUrl", v.trim()).apply()

    var navApp: NavApp
        get() = runCatching { NavApp.valueOf(sp.getString("navApp", "")!!) }.getOrDefault(NavApp.GOOGLE)
        set(v) = sp.edit().putString("navApp", v.name).apply()

    var autoNavigate: Boolean
        get() = sp.getBoolean("autoNavigate", false)
        set(v) = sp.edit().putBoolean("autoNavigate", v).apply()

    /** Address search server (Photon API). Blank = the public instance at photon.komoot.io. */
    var geocoderUrl: String
        get() = sp.getString("geocoderUrl", "") ?: ""
        set(v) = sp.edit().putString("geocoderUrl", v.trim()).apply()

    /** Floating button: which screen edge it snaps to, and its vertical position (px from top). */
    var fabRight: Boolean
        get() = sp.getBoolean("fabRight", true)
        set(v) = sp.edit().putBoolean("fabRight", v).apply()
    var fabY: Int
        get() = sp.getInt("fabY", -1)
        set(v) = sp.edit().putInt("fabY", v).apply()

    /** The permissions sheet was shown once (first launch). */
    var setupShown: Boolean
        get() = sp.getBoolean("setupShown", false)
        set(v) = sp.edit().putBoolean("setupShown", v).apply()

    /** The route start (depot) used for new routes and for search bias; mirrored into the saved route. */
    var start: RouteStart?
        get() = sp.getString("start", null)?.let { runCatching { startFromJson(JSONObject(it)) }.getOrNull() }
        set(v) = sp.edit().apply { if (v == null) remove("start") else putString("start", v.toJson().toString()) }.apply()

    var endMode: EndMode
        get() = runCatching { EndMode.valueOf(sp.getString("endMode", "")!!) }.getOrDefault(EndMode.LAST_STOP)
        set(v) = sp.edit().putString("endMode", v.name).apply()

    /**
     * Google search-as-you-type (unofficial, keyless). On by default by the user's
     * choice; off = offline pack, phone geocoder and OSM only.
     */
    var googleSuggest: Boolean
        get() = sp.getBoolean("googleSuggest", true)
        set(v) = sp.edit().putBoolean("googleSuggest", v).apply()

    /** Recently picked places (newest first, max [RECENT_MAX]); local suggestions. */
    var recents: List<Suggestion>
        get() = runCatching {
            val a = JSONArray(sp.getString("recents", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Suggestion(o.getString("t"), o.optString("s", ""),
                    if (o.has("lat")) LatLng(o.getDouble("lat"), o.getDouble("lng")) else null, SuggestSource.LOCAL)
            }
        }.getOrDefault(emptyList())
        set(v) {
            val a = JSONArray()
            v.take(RECENT_MAX).forEach { r ->
                a.put(JSONObject().put("t", r.title).put("s", r.subtitle).apply { r.point?.let { put("lat", it.lat).put("lng", it.lng) } })
            }
            sp.edit().putString("recents", a.toString()).apply()
        }

    fun addRecent(r: Suggestion) {
        val key = SuggestRanker.key(r)
        recents = listOf(r.copy(source = SuggestSource.LOCAL, approximate = false)) + recents.filter { SuggestRanker.key(it) != key }
    }

    /** Every remembered drop-off (normalized address, point): local suggestions. */
    fun rememberedPins(): List<Pair<String, LatLng>> = runCatching {
        val o = JSONObject(sp.getString("pins", "{}")!!)
        o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { k to LatLng(it.getDouble("lat"), it.getDouble("lng")) } }.toList()
    }.getOrDefault(emptyList())

    /** Show the MapLibre map above the stop list (off = list only, no tile downloads). */
    var showMap: Boolean
        get() = sp.getBoolean("showMap", true)
        set(v) = sp.edit().putBoolean("showMap", v).apply()

    /** Drop-off pins the driver corrected, remembered per normalized address. */
    val addressMemory = object : AddressMemory {
        private fun read() = JSONObject(sp.getString("pins", "{}")!!)
        override fun access(address: String): LatLng? =
            read().optJSONObject(normalizeAddress(address))?.let { LatLng(it.getDouble("lat"), it.getDouble("lng")) }

        override fun remember(address: String, access: LatLng) {
            val o = read().put(normalizeAddress(address), JSONObject().put("lat", access.lat).put("lng", access.lng))
            sp.edit().putString("pins", o.toString()).apply()
        }
    }
}

/** Platform geocoder: no key, no dependency. Blocking; call off the main thread. */
class PlatformGeocoder(ctx: Context) : Geocoder {
    private val g = AndroidGeocoder(ctx.applicationContext, Locale.getDefault())

    override fun geocode(address: String): LatLng? = runCatching { candidates(address, 1) }.getOrNull()?.firstOrNull()?.point

    /** Up to [max] matches. Empty when the device has no geocoder. Blocking. */
    @Suppress("DEPRECATION")
    fun candidates(address: String, max: Int = 5, near: LatLng? = null): List<Suggestion> =
        if (!AndroidGeocoder.isPresent()) emptyList()
        else (near?.let { c ->
            // ~55 km box around the start first; anywhere if that finds nothing.
            g.getFromLocationName(address, max, c.lat - 0.5, c.lng - 0.6, c.lat + 0.5, c.lng + 0.6).orEmpty().ifEmpty { null }
        } ?: g.getFromLocationName(address, max).orEmpty()).map { a ->
            val line = (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it).orEmpty() }.ifBlank { address }
            val (l1, l2) = splitAddress(line)
            Suggestion(l1, l2, LatLng(a.latitude, a.longitude), SuggestSource.DEVICE)
        }

    /** Street address at [p], or null (no geocoder / nothing found). */
    @Suppress("DEPRECATION")
    fun reverse(p: LatLng): String? =
        if (!AndroidGeocoder.isPresent()) null
        else runCatching { g.getFromLocation(p.lat, p.lng, 1) }.getOrNull()?.firstOrNull()?.let { a ->
            (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it).orEmpty() }.ifBlank { null }
        }
}

fun RouteStart.toJson(): JSONObject = JSONObject()
    .put("lat", point.lat).put("lng", point.lng).put("label", label).put("live", live)
    .put("fixAtMs", fixAtMs ?: JSONObject.NULL).put("accuracyM", accuracyM?.toDouble() ?: JSONObject.NULL)

fun startFromJson(o: JSONObject) = RouteStart(
    LatLng(o.getDouble("lat"), o.getDouble("lng")),
    o.optString("label", "Start"),
    o.optBoolean("live", false),
    if (o.isNull("fixAtMs")) null else o.optLong("fixAtMs"),
    if (o.isNull("accuracyM")) null else o.optDouble("accuracyM").toFloat(),
)
