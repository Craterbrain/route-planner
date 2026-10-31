package app.routeplanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.routeplanner.core.LatLng

/**
 * Current position from the platform [LocationManager] (GPS / network /
 * fused providers), so it works without Play services. Asks every enabled
 * provider for a *fresh* fix and takes the first answer; after [TIMEOUT_MS]
 * falls back to the newest last-known position.
 */
object LocationFix {
    const val TIMEOUT_MS = 20_000L

    class Fix(val point: LatLng, val accuracyM: Float?, val timeMs: Long, val provider: String)

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun Location.fix() = Fix(LatLng(latitude, longitude), if (hasAccuracy()) accuracy else null, time, provider ?: "?")

    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }
            .getOrNull()?.fix()
    }

    /**
     * Requests a fresh fix. [done] runs once on the main thread with the fix
     * (possibly a last-known fallback, flagged by [stale]) or an error message.
     */
    @SuppressLint("MissingPermission")
    fun current(ctx: Context, done: (fix: Fix?, stale: Boolean, error: String?) -> Unit) {
        if (!hasPermission(ctx)) { done(null, false, "Location permission is off."); return }
        val lm = ctx.getSystemService(LocationManager::class.java) ?: run { done(null, false, "No location service."); return }
        val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            done(null, false, "Location is turned off. Turn it on in the quick settings, or pick the start on the map.")
            return
        }
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val cancels = mutableListOf<() -> Unit>()
        fun finish(f: Fix?, stale: Boolean, err: String?) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            cancels.forEach { runCatching { it() } }
            done(f, stale, err)
        }
        for (p in providers) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cancels += { signal.cancel() }
                    lm.getCurrentLocation(p, signal, ctx.mainExecutor) { loc -> if (loc != null) finish(loc.fix(), false, null) }
                } else {
                    val l = object : LocationListener {
                        override fun onLocationChanged(loc: Location) = finish(loc.fix(), false, null)
                        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }
                    cancels += { lm.removeUpdates(l) }
                    @Suppress("DEPRECATION") lm.requestSingleUpdate(p, l, Looper.getMainLooper())
                }
            }.onFailure { Log.w(NetStatus.TAG, "location: provider $p failed: $it") }
        }
        main.postDelayed({
            val last = lastKnown(ctx)
            finish(last, true, if (last == null) "No location fix yet. Go outside or near a window and try again, or pick the start on the map." else null)
        }, TIMEOUT_MS)
    }
}
