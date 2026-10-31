package app.routeplanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.routeplanner.ui.Dialogs
import app.routeplanner.core.DeliveryReport
import app.routeplanner.core.Pkg
import app.routeplanner.core.Stop
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bundles the delivery report: report.csv plus the proof photos and signatures it refers to. */
object ProofExport {
    /** ⋮ → Export delivery report: builds the zip off the main thread, then hands it to the share sheet. */
    fun share(act: Activity, stops: List<Stop>, packages: List<Pkg>, snack: (String) -> Unit) {
        val busy = Dialogs.busy(act, "Building report…")
        Thread {
            val r = runCatching { build(act, stops, packages) }
            act.runOnUiThread {
                busy.dismiss()
                r.onSuccess { zip ->
                    val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", zip)
                    act.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "Delivery report")
                            .putExtra(Intent.EXTRA_TEXT, DeliveryReport.summary(stops))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share delivery report"))
                }.onFailure { snack("Couldn't build the report: ${it.message}") }
            }
        }.start()
    }

    /** Builds `cacheDir/exports/delivery-report.zip` (replacing any earlier one) and returns it. Run off the main thread. */
    fun build(ctx: Context, stops: List<Stop>, packages: List<Pkg>): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "delivery-report.zip")

        // One folder per kind; names carry the stop number so a file is recognisable on its own.
        val shipped = LinkedHashMap<String, File>() // original path -> entry name
        val names = HashMap<String, String>()
        for (s in stops) {
            s.proofPhoto?.let { p -> File(p).takeIf { it.exists() }?.let { names[p] = "photos/stop-${s.optimizedIndex}.jpg"; shipped[p] = it } }
            s.proofSignature?.let { p -> File(p).takeIf { it.exists() }?.let { names[p] = "signatures/stop-${s.optimizedIndex}.png"; shipped[p] = it } }
        }
        val csv = DeliveryReport.csv(stops, packages, fileName = { names[it] ?: "" })

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("report.csv"))
            zip.write(csv.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((path, file) in shipped) {
                zip.putNextEntry(ZipEntry(names.getValue(path)))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }
}
