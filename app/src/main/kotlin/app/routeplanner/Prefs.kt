package app.routeplanner

import android.content.Context
import android.location.Geocoder as AndroidGeocoder
import app.routeplanner.core.*
import org.json.JSONObject
import java.util.Locale

/** Small settings + per-address memory store (SharedPreferences, no DB yet). */
class Prefs(ctx: Context) {
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

    @Suppress("DEPRECATION")
    override fun geocode(address: String): LatLng? =
        if (!AndroidGeocoder.isPresent()) null
        else g.getFromLocationName(address, 1)?.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
}
