package app.routeplanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.routeplanner.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Process-wide holder for the active route, persisted to filesDir so a killed
 * process resumes mid-route. The activity and the overlay both observe it.
 */
object SessionStore {
    var session: RouteSession? = null
        private set
    private val listeners = mutableListOf<() -> Unit>()

    fun observe(l: () -> Unit) { listeners += l }
    fun unobserve(l: () -> Unit) { listeners -= l }

    private fun file(ctx: Context) = File(ctx.filesDir, "route.json")

    fun set(ctx: Context, s: RouteSession?) {
        attach(ctx, s)
        commit(ctx)
    }

    // Auto-save: the open session tells us whenever anything about it changes, so no caller has to remember to
    // commit(). Changes made in one go (an action touches stops, packages and the cursor) are coalesced into a single
    // save + redraw at the end of the current main-thread turn. An explicit commit() still works and settles it at once.
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @android.annotation.SuppressLint("StaticFieldLeak") // the application context, which lives as long as the process
    private var appCtx: Context? = null
    private var changePending = false
    private var changePosted = false
    private val flush = Runnable {
        changePosted = false
        if (changePending) appCtx?.let { commit(it) }
    }

    private fun attach(ctx: Context, s: RouteSession?) {
        appCtx = ctx.applicationContext
        session?.onChanged = null
        session = s
        s?.onChanged = {
            changePending = true
            if (!changePosted) { changePosted = true; main.post(flush) }
        }
    }

    // One writer thread, so saves land in order. Each commit encodes on the caller's thread (the session is only
    // touched from the main thread) and hands the text over; a save that has been overtaken by a newer one is skipped.
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "route-writer") }
    private val latest = java.util.concurrent.atomic.AtomicInteger()

    /** Persist and notify now. Optional after a mutation (the session's change hook does it at the end of the turn); the notification is immediate, the disk write is not. */
    fun commit(ctx: Context) {
        changePending = false
        val f = file(ctx)
        val json = session?.let { encode(it).toString() } // null = no route: delete the file
        val version = latest.incrementAndGet()
        writer.execute { if (version == latest.get()) write(f, json) }
        listeners.toList().forEach { it() }
    }

    private fun write(f: File, json: String?) {
        runCatching {
            if (json == null) f.delete()
            else {
                // Write-then-rename so a crash mid-write never leaves a truncated route.
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }.onFailure { AppLog.w("route save failed: ${it.javaClass.simpleName}") }
    }

    fun load(ctx: Context) {
        if (session != null) return
        val f = file(ctx)
        if (f.exists()) attach(ctx, runCatching { decode(JSONObject(f.readText())) }.getOrNull())
    }

    /** Runs a session action; launches the returned nav link (auto-navigate) from any context. */
    fun act(ctx: Context, action: RouteSession.() -> String?) {
        val s = session ?: return
        val link = s.action()
        commit(ctx)
        if (link != null) launchNav(ctx, link, s.navApp)
    }

    fun launchNav(ctx: Context, link: String, app: NavApp) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when (app) {
            NavApp.GOOGLE -> i.setPackage("com.google.android.apps.maps")
            NavApp.WAZE -> i.setPackage("com.waze")
            NavApp.APPLE -> {}
        }
        // Preferred app missing: fall back to any handler of the link.
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun JSONObject.ll(k: String) = getJSONObject(k).let { LatLng(it.getDouble("lat"), it.getDouble("lng")) }
    private fun LatLng.json() = JSONObject().put("lat", lat).put("lng", lng)
    private fun JSONObject.optStr(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
    private inline fun <reified E : Enum<E>> JSONObject.optEnum(k: String): E? =
        optStr(k)?.let { v -> enumValues<E>().firstOrNull { it.name == v } }

    internal fun encode(s: RouteSession) = JSONObject()
        .put("version", 3)
        .put("navApp", s.navApp.name)
        .put("autoNavigate", s.autoNavigate)
        .put("name", s.name ?: JSONObject.NULL)
        .put("savedId", s.savedId ?: JSONObject.NULL)
        .put("cursor", s.cursor)
        .put("startMs", s.startMs)
        .put("started", s.started)
        .put("dirty", s.dirty)
        .put("start", s.start?.toJson() ?: JSONObject.NULL)
        .put("endMode", s.end.mode.name)
        .put("endPoint", s.end.point?.json() ?: JSONObject.NULL)
        .put("endLabel", s.end.label ?: JSONObject.NULL)
        .put("endLegSec", s.endLegSec)
        .put("endLegMeters", s.endLegMeters)
        .put("stops", JSONArray(s.stops.map {
            JSONObject()
                .put("id", it.id).put("address", it.address)
                .put("target", it.target.json()).put("access", it.access.json())
                .put("serviceSec", it.serviceSec)
                .put("placement", it.placement.name)
                .put("priority", it.priority) // read by older versions
                .put("windowStart", it.window?.first ?: JSONObject.NULL)
                .put("windowEnd", it.window?.last ?: JSONObject.NULL)
                .put("notes", it.notes).put("status", it.status.name)
                .put("failReason", it.failReason?.name ?: JSONObject.NULL)
                .put("originalIndex", it.originalIndex).put("optimizedIndex", it.optimizedIndex)
                .put("etaSec", it.etaSec).put("lateSec", it.lateSec)
                .put("legMeters", it.legMeters)
                .put("completedAt", it.completedAt ?: JSONObject.NULL)
                .put("proofNote", it.proofNote ?: JSONObject.NULL)
                .put("proofPhoto", it.proofPhoto ?: JSONObject.NULL)
                .put("proofSignature", it.proofSignature ?: JSONObject.NULL)
                .put("entryNo", it.entryNo)
                .put("approximate", it.approximate)
        }))
        .put("packages", JSONArray(s.packages.map {
            JSONObject().put("id", it.id).put("stopId", it.stopId)
                .put("zone", it.truckZone ?: JSONObject.NULL).put("status", it.status.name)
                .put("codes", JSONArray(it.codes))
        }))

    /** Old routes (version 1) had no route clock; their times were shown as if the day began at 08:00. */
    private fun legacyStartMs(): Long =
        LocalDate.now().atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Every field but id/address/target is optional so routes saved by older versions still load. */
    internal fun decode(o: JSONObject): RouteSession {
        val stops = o.getJSONArray("stops").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                val target = it.ll("target")
                Stop(
                    id = it.getString("id"), address = it.getString("address"),
                    target = target, access = if (it.has("access")) it.ll("access") else target,
                    serviceSec = it.optInt("serviceSec", 120),
                    placement = it.optEnum<Placement>("placement")
                        ?: if (it.optBoolean("priority", false)) Placement.FIRST else Placement.AUTO,
                    window = if (it.isNull("windowStart") || it.isNull("windowEnd")) null
                        else it.getLong("windowStart")..it.getLong("windowEnd"),
                    notes = it.optString("notes", ""),
                    status = it.optEnum<Status>("status") ?: Status.PENDING,
                    failReason = it.optEnum<FailReason>("failReason"),
                    originalIndex = it.optInt("originalIndex", 0), optimizedIndex = it.optInt("optimizedIndex", 0),
                    etaSec = it.optInt("etaSec", 0), lateSec = it.optInt("lateSec", 0),
                    legMeters = it.optInt("legMeters", 0),
                    completedAt = if (it.isNull("completedAt")) null else it.optLong("completedAt"),
                    proofNote = it.optStr("proofNote"),
                    proofPhoto = it.optStr("proofPhoto"),
                    proofSignature = it.optStr("proofSignature"),
                    entryNo = it.optInt("entryNo", 0), // 0 = saved before address IDs; the session numbers it
                    approximate = it.optBoolean("approximate", false),
                )
            }
        }
        val pkgs = o.optJSONArray("packages")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Pkg(it.getString("id"), it.getString("stopId"), it.optStr("zone"), it.optEnum<Status>("status") ?: Status.PENDING,
                    codes = it.optJSONArray("codes")?.let { c -> (0 until c.length()).map { i -> c.getString(i) } }.orEmpty())
            }
        }.orEmpty()
        val startMs = if (o.has("startMs")) o.getLong("startMs") else legacyStartMs()
        return RouteSession(
            stops, pkgs,
            o.optEnum<NavApp>("navApp") ?: NavApp.GOOGLE,
            o.optBoolean("autoNavigate", false),
            startMs = startMs,
        ).also {
            // Version-1 routes were always "in progress": keep showing the driving card.
            it.started = o.optBoolean("started", !o.has("version"))
            it.dirty = o.optBoolean("dirty", false)
            // Version-2 routes and older have no start: the app fills it from Prefs.
            it.start = o.optJSONObject("start")?.let { j -> runCatching { startFromJson(j) }.getOrNull() }
            it.end = RouteEnd(
                o.optEnum<EndMode>("endMode") ?: EndMode.LAST_STOP,
                o.optJSONObject("endPoint")?.let { j -> LatLng(j.getDouble("lat"), j.getDouble("lng")) },
                o.optStr("endLabel"),
            )
            it.endLegSec = o.optInt("endLegSec", 0)
            it.endLegMeters = o.optInt("endLegMeters", 0)
            it.name = o.optStr("name")
            it.savedId = o.optStr("savedId")
            it.restoreCursor(o.optInt("cursor", 0))
        }
    }
}
