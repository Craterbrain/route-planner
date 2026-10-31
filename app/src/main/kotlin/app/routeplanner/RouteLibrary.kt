package app.routeplanner

import android.content.Context
import app.routeplanner.core.RouteSession
import app.routeplanner.core.Status
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Routes the driver has saved by name, one JSON file each in filesDir/routes: the same encoding the open route uses
 * ([SessionStore.encode]), so statuses, packages, proof and the start/end all come back. Blocking: call off the main thread.
 */
object RouteLibrary {
    class Entry(val id: String, val name: String, val savedAt: Long, val stops: Int, val delivered: Int)

    private fun dir(ctx: Context) = File(ctx.filesDir, "routes").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    /** Saved routes, newest first. Unreadable files are skipped. */
    fun list(ctx: Context): List<Entry> = (dir(ctx).listFiles { f -> f.extension == "json" }.orEmpty()).mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            val stops = o.getJSONObject("session").getJSONArray("stops")
            val done = (0 until stops.length()).count { stops.getJSONObject(it).optString("status") != Status.PENDING.name }
            Entry(f.nameWithoutExtension, o.optString("name", "Route"), o.optLong("savedAt"), stops.length(), done)
        }.getOrNull()
    }.sortedByDescending { it.savedAt }

    /** A save that has been encoded (cheap, on the caller's thread) and is waiting to be written ([write] may run anywhere). */
    class Prepared(val id: String, private val target: File, private val json: String) {
        fun write() {
            val tmp = File(target.parentFile, "$id.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
    }

    /**
     * Step one of saving [s] as [name]: encodes the session now (it is only safe to read on the main thread) and tells it
     * its name and entry, so the next Save of the same route updates it. Writes over the entry it came from
     * ([RouteSession.savedId]) unless [asNew]. Call [Prepared.write] off the main thread.
     */
    fun prepare(ctx: Context, s: RouteSession, name: String, asNew: Boolean = false): Prepared {
        val id = s.savedId?.takeIf { !asNew && file(ctx, it).exists() } ?: UUID.randomUUID().toString()
        s.name = name
        s.savedId = id
        val json = JSONObject().put("name", name).put("savedAt", System.currentTimeMillis()).put("session", SessionStore.encode(s))
        return Prepared(id, file(ctx, id), json.toString())
    }

    /** The saved route, or null if it can't be read. */
    fun load(ctx: Context, id: String): RouteSession? = runCatching {
        val o = JSONObject(file(ctx, id).readText())
        SessionStore.decode(o.getJSONObject("session")).also { it.name = o.optString("name").ifEmpty { null }; it.savedId = id }
    }.getOrNull()

    fun delete(ctx: Context, id: String) { file(ctx, id).delete() }
}
