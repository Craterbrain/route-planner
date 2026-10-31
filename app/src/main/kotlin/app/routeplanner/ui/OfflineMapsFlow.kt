package app.routeplanner.ui

import android.app.Activity
import android.widget.TextView
import app.routeplanner.OfflineMaps
import app.routeplanner.core.Bounds
import app.routeplanner.core.splitAddress
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * Offline map screens: the list of saved maps, the "download this route's area?" confirmation and the progress
 * dialog. The work itself is in [OfflineMaps]; [area] is what the route covers (null until there is a start),
 * [startLabel] names the download.
 */
class OfflineMapsFlow(
    private val act: Activity,
    private val area: () -> Bounds?,
    private val startLabel: () -> String?,
    private val snack: (String) -> Unit,
) {
    fun show() {
        OfflineMaps.list(act) { saved ->
            if (act.isFinishing || act.isDestroyed) return@list
            OfflineMapsDialog.show(act, saved, canDownload = area() != null,
                onDownload = { confirmDownload() },
                onDelete = { s ->
                    Dialogs.confirm(act, "Delete \"${s.name}\"?", "The saved map is removed from this phone.", "Delete") {
                        OfflineMaps.delete(s) { snack("Offline map deleted"); show() }
                    }
                })
        }
    }

    private fun confirmDownload() {
        val bounds = area() ?: run { snack("Set a starting point first so there's an area to save."); return }
        val plan = OfflineMaps.plan(bounds)
        val name = (startLabel()?.let { splitAddress(it).first } ?: "Route area") + " area"
        val msg = "About %.0f × %.0f km, zoom %d–%d, roughly %d tiles per style (light and dark). Needs a connection and stays on this phone."
            .format(Locale.ROOT, plan.widthKm, plan.heightKm, plan.minZoom, plan.maxZoom, plan.tiles) +
            if (plan.maxZoom < OfflineMaps.MAX_ZOOM) "\n\nThe area is large, so street-level detail is limited to zoom ${plan.maxZoom}." else ""
        Dialogs.confirm(act, "Download offline map?", msg, "Download") { download(name, plan) }
    }

    private fun download(name: String, plan: OfflineMaps.Plan) {
        val text = TextView(act).apply { setPadding(act.dp(24), act.dp(8), act.dp(24), 0); this.text = "Starting…" }
        var job: OfflineMaps.Job? = null
        val dlg = MaterialAlertDialogBuilder(act).setTitle("Downloading map").setView(text).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel(); snack("Download cancelled") }.show()
        job = OfflineMaps.download(act, name, plan,
            onProgress = { p -> text.text = "${p.percent}% · ${p.text}" },
            onDone = { r ->
                if (dlg.isShowing && !act.isDestroyed) dlg.dismiss()
                r.onSuccess { snack("Offline map saved: $name") }
                    .onFailure { snack("Map download failed: ${it.message}") }
            })
    }
}
