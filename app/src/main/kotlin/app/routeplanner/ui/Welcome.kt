package app.routeplanner.ui

import android.app.Activity
import com.google.android.material.R as M
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** "How it works": five short steps, shown on first launch and from Settings. */
object Welcome {
    private val STEPS = listOf(
        "1. Set your start" to "Use your location or search an address. Routes are planned from here, and address search looks around it first.",
        "2. Add stops" to "Type, speak, or scan a shipping label with the camera button; import a spreadsheet; or long-press the map. A second parcel for an address asks if it's another package.",
        "3. Optimize, then Start route" to "Navigate hands off to Google Maps or Waze. The floating button lets you mark Delivered or Failed from over them.",
        "4. Keep what matters" to "The ⋮ on the route card saves, opens and shares routes (a CSV another phone can open) and exports a delivery report.",
        "5. Works without signal" to "In Settings, import road data and address data, and download the map around your route. Search, planning and scanning then work offline.",
    )

    fun show(act: Activity, onDone: () -> Unit = {}) {
        val box = act.vertical(padH = 24, padV = 8)
        for ((title, text) in STEPS) {
            box.addFull(act.label(15f, M.attr.colorPrimary, bold = true).apply { this.text = title }, topDp = 12)
            box.addFull(act.label(14f).apply { this.text = text }, topDp = 2)
        }
        MaterialAlertDialogBuilder(act).setTitle("Welcome to Route Planner")
            .setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Got it", null)
            .setOnDismissListener { onDone() }
            .show()
    }
}
