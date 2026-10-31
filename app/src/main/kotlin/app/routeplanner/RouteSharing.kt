package app.routeplanner

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import app.routeplanner.core.RouteSession
import app.routeplanner.core.RouteShare
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sending a route to another phone through the system share sheet (a CSV file), and reading a file that arrives. */
object RouteSharing {
    private const val MAX_BYTES = 5 * 1024 * 1024

    /**
     * Builds `route-<date>.csv` in the cache and opens the share sheet (Quick Share, Bluetooth, Signal, mail...).
     * The text part is a readable list of the stops for apps that only show text. Quick; safe on the main thread.
     */
    fun share(act: Activity, s: RouteSession, includeFinished: Boolean) {
        val now = System.currentTimeMillis()
        val dir = File(act.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("route-") }?.forEach { it.delete() }
        val name = "route-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(now)) + "." + RouteShare.EXTENSION
        val file = File(dir, name).also { it.writeText(RouteShare.encode(s.stops, s.packages, s.startMs, includeFinished)) }
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(RouteShare.MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Route")
            .putExtra(Intent.EXTRA_TEXT, RouteShare.summaryText(s.stops, s.packages, includeFinished))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri) // lets the share sheet grant the receiver read access
        act.startActivity(Intent.createChooser(send, "Share route"))
    }

    /** The file behind an incoming intent (shared or opened), or null if the intent carries none. */
    fun uriFrom(i: Intent): Uri? = when (i.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    /** Reads the file's text (capped, so a wrong file can't fill memory). Blocking. */
    fun read(ctx: Context, uri: Uri): String {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Couldn't open the file.")
        input.use {
            val bytes = it.readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) throw IllegalArgumentException("That file is too big to be a route.")
            return String(bytes, Charsets.UTF_8)
        }
    }
}
