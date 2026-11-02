package app.routeplanner.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.ScrollView
import app.routeplanner.NetStatus
import com.google.android.material.R as M
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * "Set up Route Planner": one row per thing the app needs, each with its
 * current state and a button to fix it. Shown on first launch and from
 * Settings → Permissions; [refresh] re-checks (call it from onResume and
 * after permission results). Nothing here blocks the app.
 */
class PermissionsSheet(
    private val act: Activity,
    private val geocoderBase: () -> String,
    private val onClosed: () -> Unit = {},
) {
    private val dialog = BottomSheetDialog(act)
    private val body = act.vertical(padH = 20, padV = 4)
    private val netListener = { refresh() }

    val isShowing get() = dialog.isShowing

    fun show() {
        dialog.setContentView(ScrollView(act).apply { addView(body) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        NetStatus.observe(netListener)
        dialog.setOnDismissListener { NetStatus.unobserve(netListener); onClosed() }
        NetStatus.check(act, geocoderBase())
        refresh()
        dialog.show()
    }

    private fun granted(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun appSettings() = act.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}")))

    fun refresh() {
        if (!dialog.isShowing && body.childCount > 0) return
        body.removeAllViews()
        body.addFull(act.label(22f, bold = true).apply { text = "Set up Route Planner" }, topDp = 4)
        body.addFull(act.label(14f, M.attr.colorOnSurfaceVariant).apply {
            text = "Allow what you need. Skipped items only turn off the matching feature."
        }, topDp = 4, bottomDp = 8)

        val net = NetStatus.last
        row(
            "Network",
            when (net.state) {
                NetStatus.State.OK -> State.OK
                NetStatus.State.CHECKING, NetStatus.State.UNKNOWN -> State.CHECKING
                else -> State.MISSING
            },
            when (net.state) {
                NetStatus.State.OK -> "Map tiles and address search are reachable."
                NetStatus.State.CHECKING, NetStatus.State.UNKNOWN -> "Testing the map and address-search servers…"
                NetStatus.State.NO_NETWORK -> NetStatus.BLOCKED_HELP
                NetStatus.State.UNREACHABLE -> "The servers didn't answer (${net.detail}). Check VPN/firewall apps and Private DNS. " +
                    "Without network the route list, saved stops and on-device road times still work."
            },
            listOf("Test again" to { NetStatus.check(act, geocoderBase()) }) +
                if (net.state == NetStatus.State.NO_NETWORK || net.state == NetStatus.State.UNREACHABLE) listOf("App settings" to { appSettings() }) else emptyList(),
        )

        val loc = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        row("Location", if (loc) State.OK else State.MISSING,
            "Starts routes from where you are and ranks nearby addresses first. Only used while the app is open.",
            if (loc) emptyList() else listOf("Allow" to {
                if (act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !askedBefore("loc"))
                    act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ)
                else appSettings()
            }))

        if (Build.VERSION.SDK_INT >= 33) {
            val notif = granted(Manifest.permission.POST_NOTIFICATIONS)
            row("Notifications", if (notif) State.OK else State.MISSING,
                "Shows the \"Route in progress\" notification (with a Stop button) while the floating button is on.",
                if (notif) emptyList() else listOf("Allow" to {
                    if (act.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) || !askedBefore("notif"))
                        act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ)
                    else appSettings()
                }))
        }

        val overlay = Settings.canDrawOverlays(act)
        row("Display over other apps", if (overlay) State.OK else State.MISSING,
            "Needed for the floating button over Google Maps / Waze. Android only grants this from its Settings screen: find Route Planner and turn it on.",
            if (overlay) emptyList() else listOf("Open Settings" to {
                act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
            }))

        val pm = act.getSystemService(PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(act.packageName) == true
        row("Battery (optional)", if (unrestricted) State.OK else State.OPTIONAL,
            "If the floating button disappears during long routes, exempt Route Planner from battery optimization. Most phones don't need this.",
            if (unrestricted) emptyList() else listOf("Battery settings" to {
                runCatching { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.onFailure { appSettings() }
            }))

        body.addFull(act.button("Done") { dialog.dismiss() }, topDp = 12, bottomDp = 16)
    }

    private val asked = act.getSharedPreferences("route-planner", Activity.MODE_PRIVATE)
    /** First request always uses the system dialog; after a permanent denial we send people to App info. */
    private fun askedBefore(key: String): Boolean {
        val k = "asked_$key"
        val before = asked.getBoolean(k, false)
        asked.edit().putBoolean(k, true).apply()
        return before
    }

    private enum class State { OK, MISSING, OPTIONAL, CHECKING }

    private fun row(title: String, state: State, explain: String, buttons: List<Pair<String, () -> Unit>>) {
        val box = act.vertical(padH = 14, padV = 10).apply { background = act.roundRect(M.attr.colorSurfaceContainerHigh, 16) }
        val head = act.horizontal()
        head.addWeighted(act.label(16f, bold = true).apply { text = title })
        head.addView(act.label(13f, when (state) {
            State.OK -> M.attr.colorPrimary
            State.MISSING -> if (title == "Network") M.attr.colorError else M.attr.colorOnSurfaceVariant // not asked yet is not an error
            else -> M.attr.colorOnSurfaceVariant
        }, bold = true).apply {
            text = when (state) { State.OK -> "Allowed"; State.MISSING -> "Not yet"; State.OPTIONAL -> "Optional"; State.CHECKING -> "Checking…" }
            if (title == "Network" && state == State.OK) text = "Working"
            if (title == "Network" && state == State.MISSING) text = "Not working"
        })
        box.addFull(head)
        box.addFull(act.label(13f, M.attr.colorOnSurfaceVariant).apply { text = explain }, topDp = 2)
        if (buttons.isNotEmpty()) {
            val r = act.horizontal()
            buttons.forEach { (label, f) -> r.addView(act.button(label, M.attr.borderlessButtonStyle) { f() }) }
            box.addFull(r)
        }
        body.addFull(box, topDp = 8)
    }

    companion object {
        const val REQ = 7
    }
}
