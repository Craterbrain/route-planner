package app.routeplanner

import android.app.Application
import android.content.Context
import android.os.Build
import app.routeplanner.core.Diagnostics
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Keeps a short record of crashes on the phone (filesDir/crash) so they can be shared from Settings. A report holds the
 * app and Android version, the device model, the exception classes and code locations (never messages: see
 * [Diagnostics]) and the last log lines. Nothing leaves the phone unless the driver shares it.
 */
object CrashLog {
    private const val KEEP = 10
    private fun dir(ctx: Context) = File(ctx.filesDir, "crash").apply { mkdirs() }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val f = File(dir(app), "crash-${System.currentTimeMillis()}.txt")
                f.writeText(header(app) + "thread: ${thread.name}\n" + Diagnostics.describe(e) + "\nlast log lines:\n" + AppLog.snapshot().takeLast(120).joinToString("\n") + "\n")
                dir(app).listFiles()?.sortedBy { it.name }?.dropLast(KEEP)?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, e) // let Android show its usual "stopped" handling
        }
    }

    private fun header(ctx: Context): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("?")
        return "Route Planner $version\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}\n"
    }

    fun files(ctx: Context): List<File> = dir(ctx).listFiles()?.filter { it.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    /** When the newest crash happened, or null if none is recorded. */
    fun lastCrashAt(ctx: Context): Long? = files(ctx).firstOrNull()?.name?.removePrefix("crash-")?.removeSuffix(".txt")?.toLongOrNull()

    /** One line for Settings: how many crashes are on record and when the last was. */
    fun summary(ctx: Context): String {
        val n = files(ctx).size
        val last = lastCrashAt(ctx) ?: return "No crashes recorded."
        return "$n crash" + (if (n == 1) "" else "es") + " recorded, last " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last)) + "."
    }

    /** Everything worth sending: device, crashes (newest first) and the recent log. */
    fun report(ctx: Context): String = buildString {
        append(header(ctx)).append("\n")
        val crashes = files(ctx)
        append("== crashes: ${crashes.size} ==\n")
        crashes.take(3).forEach { append("--- ").append(it.name).append(" ---\n").append(runCatching { it.readText() }.getOrDefault("(unreadable)\n")).append("\n") }
        append("== recent log ==\n").append(AppLog.snapshot().joinToString("\n")).append("\n")
    }

    fun clear(ctx: Context) { files(ctx).forEach { it.delete() } }
}
