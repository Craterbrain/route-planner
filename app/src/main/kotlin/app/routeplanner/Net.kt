package app.routeplanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import app.routeplanner.core.PhotonGeocoder
import java.net.HttpURLConnection
import java.net.URL

/**
 * One shared answer to "can this app reach the internet?", used by the map
 * status chip, the address search and the permissions sheet, so a blocked
 * network (e.g. GrapheneOS's per-app Network permission off) reads the same
 * everywhere instead of as a generic failure.
 */
object NetStatus {
    const val TAG = "RoutePlanner"

    enum class State { UNKNOWN, CHECKING, OK, NO_NETWORK, UNREACHABLE }

    data class Result(val state: State, val detail: String = "")

    @Volatile var last = Result(State.UNKNOWN)
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    fun observe(l: () -> Unit) { listeners += l }
    fun unobserve(l: () -> Unit) { listeners -= l }

    const val BLOCKED_HELP = "Route Planner can't reach the internet. On GrapheneOS open App info → Permissions → Network and allow it (also check VPN/firewall apps and Private DNS)."

    /** Short label for chips / search errors. */
    fun shortLabel(r: Result = last): String = when (r.state) {
        State.OK -> "online"
        State.NO_NETWORK -> "No network access"
        State.UNREACHABLE -> "Servers unreachable"
        State.CHECKING -> "checking network…"
        State.UNKNOWN -> "network not checked"
    }

    /** True when the system reports no usable network for this app. */
    fun systemSaysOffline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(n) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Any HTTP answer (even 404) proves the host is reachable. */
    private fun reachable(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "HEAD"
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("User-Agent", userAgent())
            c.responseCode
            null
        } finally { c.disconnect() }
    } catch (e: Exception) {
        "${URL(url).host}: ${e.javaClass.simpleName}${e.message?.let { " ($it)" } ?: ""}"
    }

    /** Re-checks off the UI thread; listeners run on the main thread afterwards. */
    fun check(ctx: Context, geocoderBase: String) {
        if (running) return
        running = true
        last = Result(State.CHECKING)
        fire()
        val app = ctx.applicationContext
        Thread {
            val r = if (systemSaysOffline(app)) Result(State.NO_NETWORK, "The system reports no network for this app.")
            else {
                val errors = listOfNotNull(reachable(MAP_STYLE_LIGHT), reachable(geocoderBase.ifBlank { PhotonGeocoder.DEFAULT_URL }))
                if (errors.isEmpty()) Result(State.OK) else Result(State.UNREACHABLE, errors.joinToString("; "))
            }
            if (r.state != State.OK) AppLog.w("network check: ${r.state} ${r.detail}")
            last = r
            running = false
            fire()
        }.start()
    }

    private fun fire() = main.post { listeners.toList().forEach { it() } }

    fun userAgent() = "app.routeplanner/${BuildConfigLite.VERSION} (Android delivery route planner)"

    const val MAP_STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
    const val MAP_STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
}

/** Version string without enabling BuildConfig generation. */
object BuildConfigLite {
    const val VERSION = "0.6.0"
}
