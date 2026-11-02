package app.routeplanner

import android.app.Activity
import app.routeplanner.core.Pruning
import app.routeplanner.ui.Dialogs
import app.routeplanner.ui.addFull
import app.routeplanner.ui.label
import app.routeplanner.ui.vertical
import com.google.android.material.R as M
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Settings → Manage storage: what the app holds and the cleanups that are safe (photos, share files, logs). */
class StorageFlow(private val act: Activity, private val actions: RouteActions, private val ui: RouteActions.Feedback) {

    fun show() {
        actions.background({ Storage.usage(act) }) { r ->
            val u = r.getOrNull() ?: return@background
            OfflineMaps.list(act) { maps -> if (!act.isFinishing && !act.isDestroyed) dialog(u, maps.sumOf { it.bytes }) }
        }
    }

    private fun dialog(u: Storage.Usage, mapsBytes: Long) {
        val box = act.vertical(padH = 24, padV = 8)
        fun row(name: String, detail: String) {
            box.addFull(act.label(15f, bold = true).apply { text = name }, topDp = 8)
            box.addFull(act.label(13f, M.attr.colorOnSurfaceVariant).apply { text = detail })
        }
        row("Total", Pruning.size(u.total + mapsBytes))
        row("Proof photos and signatures", "${u.proofFiles} file" + (if (u.proofFiles == 1) "" else "s") + ", " + Pruning.size(u.proofBytes))
        row("Saved routes", "${u.routes} route" + (if (u.routes == 1) "" else "s") + ", " + Pruning.size(u.routesBytes) + " (delete them from Open route)")
        row("Offline maps", Pruning.size(mapsBytes) + " (Settings → Offline maps)")
        row("Road data", Pruning.size(u.roadDataBytes))
        row("Address data", Pruning.size(u.addressDataBytes))
        row("Shared report files", Pruning.size(u.exportsBytes))
        row("Diagnostic log", Pruning.size(u.logsBytes))
        MaterialAlertDialogBuilder(act).setTitle("Storage").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Delete photos…") { _, _ -> chooseProofCleanup() }
            .setNeutralButton("Clear shared files") { _, _ -> Storage.clearExports(act); ui.snack("Shared report files cleared") }
            .setNegativeButton("Close", null).show()
    }

    /** Which proof photos and signatures to remove: older than a date, or everything the open route doesn't show. */
    private fun chooseProofCleanup() {
        actions.background({ Storage.proofFiles(act) }) { r ->
            val all = r.getOrNull().orEmpty()
            val keep = Storage.inUse()
            val now = System.currentTimeMillis()
            val options = listOf("Older than 30 days" to 30, "Older than 90 days" to 90, "All except the open route's" to null)
            val picks = options.map { (label, days) -> Triple(label, days, Pruning.selectProof(all, now, days, keep)) }
            MaterialAlertDialogBuilder(act).setTitle("Delete proof photos")
                .setItems(picks.map { (label, _, files) -> "$label: ${files.size} file" + (if (files.size == 1) "" else "s") + ", " + Pruning.size(files.sumOf { it.bytes }) }.toTypedArray()) { _, i ->
                    val (label, _, files) = picks[i]
                    if (files.isEmpty()) { ui.snack("Nothing to delete"); return@setItems }
                    Dialogs.confirm(act, "Delete ${files.size} file" + (if (files.size == 1) "" else "s") + "?",
                        "$label. Photos and signatures the open route shows are kept. A saved route that used a deleted one will show it as missing. This can't be undone.", "Delete") {
                        actions.background({ Storage.delete(files) }) { d -> ui.snack("Deleted ${d.getOrDefault(0)} file(s)") }
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
    }
}
