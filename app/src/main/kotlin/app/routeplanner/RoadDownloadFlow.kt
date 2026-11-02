package app.routeplanner

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.routeplanner.ui.*
import com.google.android.material.R as M
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * "Download road data": pick a state from the published list, then download, verify and install it with progress.
 * [blocking] is the first-launch gate: no dismissing without data, but a manual file import stays available.
 */
class RoadDownloadFlow(
    private val act: Activity,
    private val onInstalled: () -> Unit,
    private val importFile: () -> Unit,
) {
    private val alive get() = !act.isFinishing && !act.isDestroyed
    @Volatile private var cancel = false
    private var addresses: Map<String, RoadDownload.AddrPack> = emptyMap()
    var showing = false
        private set

    fun show(blocking: Boolean, preselect: String? = null) {
        if (showing || !alive) return
        showing = true
        val wait = MaterialAlertDialogBuilder(act).setTitle("Offline data").setMessage("Checking what's available…")
            .setCancelable(!blocking)
            .setOnCancelListener { showing = false }
            .also { b -> if (blocking) b.setNeutralButton("Import a file") { _, _ -> showing = false; importFile() } else b.setNegativeButton("Cancel") { _, _ -> showing = false } }
            .show()
        Thread {
            val r = runCatching { RoadDownload.manifest().also { addresses = RoadDownload.addressManifest() } }
            act.runOnUiThread {
                if (!alive) { showing = false; return@runOnUiThread }
                wait.dismiss()
                r.fold({ choose(it, blocking, preselect) }, { fail("Couldn't get the list of road data: ${it.message}", blocking) { showing = false; show(blocking, preselect) } })
            }
        }.start()
    }

    private fun fail(msg: String, blocking: Boolean, retry: () -> Unit) {
        val b = MaterialAlertDialogBuilder(act).setTitle("Offline data").setMessage(msg).setCancelable(!blocking)
            .setPositiveButton("Try again") { _, _ -> retry() }
            .setOnCancelListener { showing = false }
        if (blocking) b.setNeutralButton("Import a file") { _, _ -> showing = false; importFile() }
        else b.setNegativeButton("Close") { _, _ -> showing = false }
        b.show()
    }

    private fun choose(packs: List<RoadDownload.Pack>, blocking: Boolean, preselect: String?) {
        if (packs.isEmpty()) { fail("No road data has been published yet.", blocking) { showing = false; show(blocking, preselect) }; return }
        var sel = packs.indexOfFirst { it.state == (preselect ?: "tennessee") }.coerceAtLeast(0)
        // One state at a time: the road tiles and the address pack each hold a single state. Checking another unchecks the first.
        val boxes = ArrayList<MaterialCheckBox>()
        val list = act.vertical(padH = 16, padV = 4)
        packs.forEachIndexed { i, p ->
            val cb = MaterialCheckBox(act).apply {
                text = "${p.title}  ·  ${(p.downloadBytes + (addresses[p.state]?.gzBytes ?: 0L)) / 1_000_000} MB"
                textSize = 16f
                isChecked = i == sel
                setOnClickListener { sel = if (isChecked) i else -1; boxes.forEachIndexed { j, o -> o.isChecked = j == sel } }
            }
            boxes += cb
            list.addFull(cb)
        }
        val b = MaterialAlertDialogBuilder(act).setTitle("Download offline data")
            .setView(android.widget.ScrollView(act).apply { addView(list) })
            .setPositiveButton("Download") { _, _ -> if (sel >= 0) download(packs[sel], blocking) else choose(packs, blocking, preselect) }
            .setCancelable(!blocking)
            .setOnCancelListener { showing = false }
        if (blocking) b.setNeutralButton("Import a file") { _, _ -> showing = false; importFile() }
        else b.setNegativeButton("Cancel") { _, _ -> showing = false }
        b.show()
    }

    private fun download(pack: RoadDownload.Pack, blocking: Boolean) {
        cancel = false
        val box = act.vertical(padH = 24, padV = 8)
        val status = act.label(14f).apply { text = "Starting…" }
        val bar = LinearProgressIndicator(act).apply { max = 1000; isIndeterminate = true }
        box.addFull(status); box.addFull(bar, topDp = 12)
        box.addFull(act.label(12f, M.attr.colorOnSurfaceVariant).apply {
            text = "Roads © OpenStreetMap contributors, built with Valhalla. Wi-Fi is best: this is ${(pack.downloadBytes + (addresses[pack.state]?.gzBytes ?: 0L)) / 1_000_000} MB (roads${if (addresses.containsKey(pack.state)) " and house addresses" else ""}). If it stops, open this again to resume."
        }, topDp = 12)
        val dlg: AlertDialog = MaterialAlertDialogBuilder(act).setTitle("${pack.title} data").setView(box).setCancelable(false)
            .setNegativeButton("Cancel", null).setNeutralButton("Hide", null).show()
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { cancel = true; status.text = "Cancelling…" }
        // Hide: the download carries on in the background; you are told when it finishes (or fails).
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { dlg.dismiss(); Toast.makeText(act, "Downloading in the background", Toast.LENGTH_SHORT).show() }
        Thread {
            val r = runCatching {
                RoadDownload.install(act, pack, addresses[pack.state], { cancel }, { done, total ->
                    act.runOnUiThread {
                        bar.isIndeterminate = false; bar.progress = (done * 1000 / total.coerceAtLeast(1)).toInt()
                        status.text = "${done / 1_000_000} of ${total / 1_000_000} MB · ${(done * 100 / total.coerceAtLeast(1))}%"
                    }
                }, { p -> act.runOnUiThread { status.text = p; if (p != "Downloading") bar.isIndeterminate = true } })
            }
            act.runOnUiThread {
                showing = false
                if (alive) dlg.dismiss()
                val e = r.exceptionOrNull()
                when {
                    e == null -> { onInstalled();  if (alive) MaterialAlertDialogBuilder(act).setTitle("Offline data installed").setMessage("${pack.title} routing${if (addresses.containsKey(pack.state)) " and address search" else ""} now works offline.").setPositiveButton("OK", null).show() }
                    e is RoadDownload.Cancelled -> if (blocking) show(true)
                    else -> { AppLog.w("road download failed", e); if (alive) { showing = true; fail("Download failed: ${e.message}", blocking) { showing = false; show(blocking) } } }
                }
            }
        }.start()
    }
}
