package app.routeplanner

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Settings → Share diagnostic log: the report as a text file through the share sheet. */
object DiagnosticsShare {
    fun share(act: Activity) {
        val dir = File(act.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "route-planner-log.txt").also { it.writeText(CrashLog.report(act)) }
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Route Planner diagnostic log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        act.startActivity(Intent.createChooser(send, "Share diagnostic log"))
    }
}
