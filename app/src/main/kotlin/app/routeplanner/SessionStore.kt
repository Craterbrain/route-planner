package app.routeplanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.routeplanner.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        session = s
        commit(ctx)
    }

    /** Persist and notify; call after every mutation of [session]. */
    fun commit(ctx: Context) {
        val s = session
        if (s == null) file(ctx).delete() else file(ctx).writeText(encode(s).toString())
        listeners.toList().forEach { it() }
    }

    fun load(ctx: Context) {
        if (session != null) return
        val f = file(ctx)
        if (f.exists()) session = runCatching { decode(JSONObject(f.readText())) }.getOrNull()
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

    private fun encode(s: RouteSession) = JSONObject()
        .put("navApp", s.navApp.name)
        .put("autoNavigate", s.autoNavigate)
        .put("cursor", s.cursor)
        .put("stops", JSONArray(s.stops.map {
            JSONObject()
                .put("id", it.id).put("address", it.address)
                .put("target", it.target.json()).put("access", it.access.json())
                .put("serviceSec", it.serviceSec).put("priority", it.priority)
                .put("windowStart", it.window?.first ?: JSONObject.NULL)
                .put("windowEnd", it.window?.last ?: JSONObject.NULL)
                .put("notes", it.notes).put("status", it.status.name)
                .put("failReason", it.failReason?.name ?: JSONObject.NULL)
                .put("originalIndex", it.originalIndex).put("optimizedIndex", it.optimizedIndex)
                .put("etaSec", it.etaSec).put("lateSec", it.lateSec)
                .put("completedAt", it.completedAt ?: JSONObject.NULL)
        }))
        .put("packages", JSONArray(s.packages.map {
            JSONObject().put("id", it.id).put("stopId", it.stopId)
                .put("zone", it.truckZone ?: JSONObject.NULL).put("status", it.status.name)
        }))

    private fun decode(o: JSONObject): RouteSession {
        val stops = o.getJSONArray("stops").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Stop(
                    id = it.getString("id"), address = it.getString("address"),
                    target = it.ll("target"), access = it.ll("access"),
                    serviceSec = it.getInt("serviceSec"), priority = it.getBoolean("priority"),
                    window = if (it.isNull("windowStart")) null else it.getLong("windowStart")..it.getLong("windowEnd"),
                    notes = it.getString("notes"), status = Status.valueOf(it.getString("status")),
                    failReason = if (it.isNull("failReason")) null else FailReason.valueOf(it.getString("failReason")),
                    originalIndex = it.getInt("originalIndex"), optimizedIndex = it.getInt("optimizedIndex"),
                    etaSec = it.getInt("etaSec"), lateSec = it.getInt("lateSec"),
                    completedAt = if (it.isNull("completedAt")) null else it.getLong("completedAt"),
                )
            }
        }
        val pkgs = o.getJSONArray("packages").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Pkg(it.getString("id"), it.getString("stopId"),
                    if (it.isNull("zone")) null else it.getString("zone"), Status.valueOf(it.getString("status")))
            }
        }
        return RouteSession(stops, pkgs, NavApp.valueOf(o.getString("navApp")), o.getBoolean("autoNavigate"))
            .also { it.restoreCursor(o.getInt("cursor")) }
    }
}
