package app.routeplanner

import android.content.Context
import app.routeplanner.core.DeliveryReport
import app.routeplanner.core.Pkg
import app.routeplanner.core.Stop
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bundles the delivery report: report.csv plus the proof photos and signatures it refers to. */
object ProofExport {
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
