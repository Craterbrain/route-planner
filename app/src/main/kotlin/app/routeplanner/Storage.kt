package app.routeplanner

import android.content.Context
import app.routeplanner.core.Pruning
import java.io.File

/** What the app keeps on the phone, and how to clear the parts that grow. Blocking: call off the main thread. */
object Storage {
    class Usage(
        val proofBytes: Long, val proofFiles: Int,
        val routesBytes: Long, val routes: Int,
        val exportsBytes: Long, val logsBytes: Long,
        val roadDataBytes: Long, val addressDataBytes: Long,
    ) {
        val total get() = proofBytes + routesBytes + exportsBytes + logsBytes + roadDataBytes + addressDataBytes
    }

    private fun size(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { size(it) } ?: 0L
    private fun proofDir(ctx: Context) = File(ctx.filesDir, "proof")

    fun usage(ctx: Context): Usage {
        val proof = proofFiles(ctx)
        val routes = File(ctx.filesDir, "routes").listFiles { f -> f.extension == "json" }.orEmpty()
        return Usage(
            proofBytes = proof.sumOf { it.bytes }, proofFiles = proof.size,
            routesBytes = routes.sumOf { it.length() }, routes = routes.size,
            exportsBytes = size(File(ctx.cacheDir, "exports")), logsBytes = size(File(ctx.filesDir, "crash")),
            roadDataBytes = Routing.tilesFile(ctx).let { if (it.exists()) it.length() else 0L },
            addressDataBytes = File(ctx.filesDir, "addresses.sqlite").let { if (it.exists()) it.length() else 0L },
        )
    }

    fun proofFiles(ctx: Context): List<Pruning.FileInfo> =
        proofDir(ctx).listFiles { f -> f.isFile }.orEmpty().map { Pruning.FileInfo(it.absolutePath, it.lastModified(), it.length()) }

    /** Paths of the proof photos and signatures the open route still shows. */
    fun inUse(): Set<String> = SessionStore.session?.stops.orEmpty().flatMap { listOfNotNull(it.proofPhoto, it.proofSignature) }.toSet()

    fun delete(files: List<Pruning.FileInfo>): Int = files.count { File(it.path).delete() }

    fun clearExports(ctx: Context) { File(ctx.cacheDir, "exports").listFiles()?.forEach { it.delete() } }
}
