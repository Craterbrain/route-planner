package app.routeplanner

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's log: writes to logcat as before and keeps the last few hundred lines in memory so a crash report (or the
 * Settings "Share diagnostic log" button) can include them. Lines are system and map messages; nothing the driver types
 * is ever logged, and an exception is recorded by its class name only.
 */
object AppLog {
    /** Set from the Settings switch (default off): when false nothing is written to logcat or kept in memory. */
    @Volatile var enabled = false
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun i(msg: String, t: Throwable? = null) = add(Log.INFO, 'I', msg, t)
    fun w(msg: String, t: Throwable? = null) = add(Log.WARN, 'W', msg, t)
    fun e(msg: String, t: Throwable? = null) = add(Log.ERROR, 'E', msg, t)

    private fun add(priority: Int, level: Char, msg: String, t: Throwable?) {
        if (!enabled) return
        if (t != null) Log.println(priority, NetStatus.TAG, msg + "\n" + Log.getStackTraceString(t)) else Log.println(priority, NetStatus.TAG, msg)
        val line = "${clock.format(Date())} $level $msg" + (t?.let { " [${it.javaClass.simpleName}]" } ?: "")
        synchronized(lines) { lines.addLast(line.take(300)); while (lines.size > MAX) lines.removeFirst() }
    }

    fun clear() = synchronized(lines) { lines.clear() }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}
