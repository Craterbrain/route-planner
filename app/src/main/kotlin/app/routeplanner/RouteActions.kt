package app.routeplanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import app.routeplanner.core.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.LocalTime
import java.util.UUID

/**
 * Route operations that need geocoding or road times, run off the UI thread:
 * import a spreadsheet, add a stop, optimize, and "update" (fresh ETAs, same
 * order). Results are applied on the UI thread and committed to [SessionStore].
 */
class RouteActions(private val act: Activity, private val prefs: Prefs, private val ui: Feedback) {
    interface Feedback {
        fun snack(text: String, action: String? = null, onAction: (() -> Unit)? = null)
    }

    /** Only the newest re-plan is applied; older ones finishing late are dropped. */
    private var generation = 0
    private var askedLocation = false

    private val alive get() = !act.isFinishing && !act.isDestroyed

    /** Runs [work] off the main thread, then [done] on it. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        Thread { val r = runCatching(work); act.runOnUiThread { done(r) } }.start()
    }

    private fun busy(title: String, message: String? = null): AlertDialog? = if (!alive) null else
        MaterialAlertDialogBuilder(act).setTitle(title).setMessage(message).setCancelable(false).show()

    private fun AlertDialog?.close() { if (this != null && isShowing && alive) runCatching { dismiss() } }

    fun alert(title: String, message: String) {
        if (alive) MaterialAlertDialogBuilder(act).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    /** Last known fix if permitted (asks once per process); null when unknown. */
    @SuppressLint("MissingPermission")
    fun lastLocation(): LatLng? {
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!askedLocation) { askedLocation = true; act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION) }
            return null
        }
        val lm = act.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }
            .getOrNull()?.let { LatLng(it.latitude, it.longitude) }
    }

    private fun centroid(stops: List<Stop>) =
        LatLng(stops.map { it.access.lat }.average(), stops.map { it.access.lng }.average())

    /** Where re-planning starts: GPS fix, else the last finished stop, else the middle of the route. */
    private fun origin(s: RouteSession): LatLng? =
        lastLocation() ?: s.stops.lastOrNull { it.status != Status.PENDING }?.access
            ?: s.stops.takeIf { it.isNotEmpty() }?.let { centroid(it) }

    /**
     * Re-plans the remaining stops: [optimize] = new order ("Optimize route"),
     * otherwise keep the order and refresh ETAs/distances ("Update").
     */
    fun replan(optimize: Boolean, showBusy: Boolean, after: ((Solver.Result) -> Unit)? = null) {
        val s = SessionStore.session ?: return
        if (s.stops.none { it.status == Status.PENDING }) { s.dirty = false; SessionStore.commit(act); return }
        val from = origin(s) ?: return
        val my = ++generation
        val dlg = if (showBusy) busy(if (optimize) "Optimizing route…" else "Updating route…") else null
        background({
            val src = Routing.source(act, prefs)
            s.planRemaining(src.provider, from, optimize = optimize) to src
        }) { r ->
            dlg.close()
            r.onSuccess { (plan, src) ->
                if (SessionStore.session !== s || my != generation) return@onSuccess // superseded
                s.applyPlan(plan, keepCursor = !optimize)
                SessionStore.commit(act)
                if (optimize && alive) {
                    val degraded = (src.provider as? ValhallaMatrix)?.degraded == true
                    ui.snack("Optimized · ${plan.stops.size} stops · times from ${src.label}" +
                        (if (degraded) " (some estimated)" else "") +
                        (if (plan.lateStops > 0) " · ${plan.lateStops} late" else ""))
                }
                after?.invoke(plan)
            }.onFailure {
                if (showBusy) alert("Couldn't plan the route", it.message ?: it.toString())
            }
        }
    }

    /** Imports a manifest CSV and optimizes it as a new (not yet started) route. */
    fun importCsv(uri: Uri) {
        val dlg = busy("Planning route…", "Geocoding and routing")
        val start = lastLocation()
        background({
            val text = act.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Couldn't read the file")
            val nowMs = System.currentTimeMillis()
            // Route clock zero = now, so HH:MM windows become seconds from now.
            val m = importManifest(text, dayStartSec = LocalTime.now().toSecondOfDay().toLong(),
                geocoder = PlatformGeocoder(act), memory = prefs.addressMemory)
            val src = Routing.source(act, prefs)
            val origin = start ?: if (m.stops.isEmpty()) null else centroid(m.stops)
            Triple(m, origin?.let { Solver.optimize(it, m.stops, src.provider) }, src) to nowMs
        }) { r ->
            dlg.close()
            val (res, nowMs) = r.getOrElse { alert("Import failed", it.message ?: it.toString()); return@background }
            val (m, route, src) = res
            if (route == null || route.stops.isEmpty()) {
                val why = m.errors.joinToString("\n").ifEmpty { "No rows" }
                alert("Nothing imported", why + if (!android.location.Geocoder.isPresent()) "\n\nThis device has no geocoder: include lat,lng columns." else "")
                return@background
            }
            SessionStore.set(act, RouteSession(route.stops, m.packages, prefs.navApp, prefs.autoNavigate, startMs = nowMs))
            val degraded = (src.provider as? ValhallaMatrix)?.degraded == true
            val msg = "${route.stops.size} stops, about ${formatDuration(route.totalSec)}\nTimes: ${src.label}" +
                (if (degraded) " (some legs estimated)" else "") +
                (if (route.lateStops > 0) "\n${route.lateStops} stops can't be reached on time" else "") +
                (if (m.errors.isNotEmpty()) "\n\nSkipped:\n" + m.errors.joinToString("\n") else "") +
                "\n\nTap Start route when you're ready."
            alert("Route planned", msg)
        }
    }

    /** Search-bar entry: geocodes [text]; one hit is added, several are offered to choose from. */
    fun addAddress(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        if (!android.location.Geocoder.isPresent()) {
            alert("No address search on this device", "This phone has no geocoding service. Long-press the map to drop a stop, or import a spreadsheet with lat,lng columns.")
            return
        }
        background({ PlatformGeocoder(act).candidates(q, 5) }) { r ->
            val hits = r.getOrDefault(emptyList())
            when {
                hits.isEmpty() -> ui.snack("Couldn't find “$q”")
                hits.size == 1 -> addStopAt(hits[0].label, hits[0].point, typed = q)
                alive -> MaterialAlertDialogBuilder(act).setTitle("Choose address")
                    .setItems(hits.map { it.label }.toTypedArray()) { _, i -> addStopAt(hits[i].label, hits[i].point, typed = q) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    /** Adds a stop (door at [door]) to the route, creating a route if there is none, then refreshes ETAs. */
    fun addStopAt(address: String, door: LatLng, typed: String? = null) {
        val pin = prefs.addressMemory.access(address) ?: typed?.let { prefs.addressMemory.access(it) }
        val stop = Stop(UUID.randomUUID().toString(), address, door, pin ?: door)
        val s = SessionStore.session
        if (s == null) {
            SessionStore.set(act, RouteSession(listOf(stop.copy(optimizedIndex = 1)), emptyList(), prefs.navApp, prefs.autoNavigate))
        } else {
            s.addStop(stop)
            SessionStore.commit(act)
        }
        val n = SessionStore.session?.stop(stop.id)?.optimizedIndex ?: 1
        replan(optimize = false, showBusy = false)
        ui.snack("Added stop $n", "Optimize") { replan(optimize = true, showBusy = true) }
    }

    /** Copies a valhalla_tiles.tar into app storage for on-device routing. */
    fun importTiles(uri: Uri) {
        val dlg = busy("Importing map data…")
        background({
            val tmp = File(act.filesDir, "valhalla_tiles.tar.part")
            (act.contentResolver.openInputStream(uri) ?: error("Couldn't read the file")).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val dest = Routing.tilesFile(act)
            check(tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) { "Couldn't move the file into place" }
            Routing.source(act, prefs).label
        }) { r ->
            dlg.close()
            ui.snack(r.fold({ "Routing with $it" }, { "Import failed: ${it.message}" }))
        }
    }

    companion object {
        const val REQ_LOCATION = 2
    }
}
