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
import app.routeplanner.ui.imperialUnits
import app.routeplanner.ui.totalPackages
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
    fun lastLocation(ask: Boolean = true): LatLng? {
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (ask && !askedLocation) { askedLocation = true; act.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION) }
            return null
        }
        val lm = act.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }
            .getOrNull()?.let { LatLng(it.latitude, it.longitude) }
    }

    /** Where address search should look first: the phone, else the route. Never prompts. */
    fun searchBias(): LatLng? = (SessionStore.session?.start ?: prefs.start)?.point
        ?: lastLocation(ask = false)
        ?: SessionStore.session?.stops?.takeIf { it.isNotEmpty() }?.let { centroid(it) }

    /** A new route with the current start and end settings. */
    fun newSession(stops: List<Stop>, pkgs: List<Pkg>, startMs: Long? = null) =
        RouteSession(stops, pkgs, prefs.navApp, prefs.autoNavigate, startMs = startMs).also {
            it.start = prefs.start
            it.end = prefs.end
        }

    fun addressSearch(): AddressSearch {
        val bias = searchBias()
        return AddressSearch(act, prefs) { bias }
    }

    private fun centroid(stops: List<Stop>) =
        LatLng(stops.map { it.access.lat }.average(), stops.map { it.access.lng }.average())

    /**
     * Where planning starts: the route start before any stop is done; once
     * under way the driver's position, else the last finished stop.
     */
    private fun origin(s: RouteSession): LatLng? {
        val underway = s.started && s.stops.any { it.status != Status.PENDING }
        return s.planOrigin(if (underway) lastLocation() else null)
            ?: s.stops.takeIf { it.isNotEmpty() }?.let { centroid(it) }
    }

    /**
     * Re-plans the remaining stops: [optimize] = new order ("Optimize route"),
     * otherwise keep the order and refresh ETAs/distances ("Update").
     */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingReplan = Runnable { replan(optimize = false, showBusy = false) }

    /**
     * Times for a changed route, but only once adding has paused: each replan asks the on-device road engine for a matrix
     * over every stop, so re-running it after each of 25 quick additions is 25 full runs (and a warm phone).
     */
    fun replanSoon() {
        handler.removeCallbacks(pendingReplan)
        handler.postDelayed(pendingReplan, REPLAN_DELAY_MS)
    }

    fun replan(optimize: Boolean, showBusy: Boolean, after: ((Solver.Result) -> Unit)? = null) {
        handler.removeCallbacks(pendingReplan)
        val s = SessionStore.session ?: return
        if (s.stops.none { it.status == Status.PENDING }) { s.dirty = false; SessionStore.commit(act); return }
        val from = origin(s) ?: return
        val my = ++generation
        val before = s.summary().remainingSec
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
                    val saved = (before - s.summary().remainingSec) / 60
                    ui.snack("Optimized · ${plan.stops.size} stops" + (if (saved >= 1) " · saved $saved min" else " · already the best order found") +
                        " · times from ${src.label}" +
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
        val start = prefs.start?.point ?: lastLocation()
        val search = addressSearch()
        background({
            val text = act.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Couldn't read the file")
            val nowMs = System.currentTimeMillis()
            // Route clock zero = now, so HH:MM windows become seconds from now.
            val m = importManifest(text, dayStartSec = LocalTime.now().toSecondOfDay().toLong(),
                geocoder = search.geocoder(), memory = prefs.addressMemory)
            val src = Routing.source(act, prefs)
            val origin = start ?: if (m.stops.isEmpty()) null else centroid(m.stops)
            val end = when (prefs.end.mode) { EndMode.ROUND_TRIP -> origin; EndMode.ADDRESS -> prefs.end.point; else -> null }
            Triple(m, origin?.let { Solver.optimize(it, m.stops, src.provider, end = end) }, src) to nowMs
        }) { r ->
            dlg.close()
            val (res, nowMs) = r.getOrElse { alert("Import failed", it.message ?: it.toString()); return@background }
            val (m, route, src) = res
            if (route == null || route.stops.isEmpty()) {
                val why = m.errors.joinToString("\n").ifEmpty { "No rows" }
                alert("Nothing imported", why + if (NetStatus.systemSaysOffline(act)) "\n\nNo network access, so addresses without lat,lng couldn't be looked up." else "")
                return@background
            }
            SessionStore.set(act, newSession(route.stops, m.packages, startMs = nowMs).also { it.applyPlan(route) })
            val degraded = (src.provider as? ValhallaMatrix)?.degraded == true
            val msg = "${route.stops.size} stops, about ${formatDuration(route.totalSec)}\nTimes: ${src.label}" +
                (if (degraded) " (some legs estimated)" else "") +
                (if (route.lateStops > 0) "\n${route.lateStops} stops can't be reached on time" else "") +
                (if (m.warnings.isNotEmpty()) "\n\nEstimated positions (adjust the pin):\n" + m.warnings.joinToString("\n") else "") +
                (if (m.errors.isNotEmpty()) "\n\nSkipped:\n" + m.errors.joinToString("\n") else "") +
                "\n\nTap Start route when you're ready."
            alert("Route planned", msg)
        }
    }

    /**
     * Adds a stop (door at [door]) to the route, creating a route if there is none, then refreshes ETAs. When the
     * address is already on the route (still to do) it asks whether this is another package for it instead of
     * silently duplicating it; the result is then null (the answer arrives later). Otherwise returns the new stop's id.
     */
    fun addStopAt(address: String, door: LatLng, typed: String? = null, approximate: Boolean = false, packageId: String? = null,
                  notes: String = "", codes: List<String> = emptyList()): String? {
        val s0 = SessionStore.session
        // The same parcel scanned twice: say so instead of adding it again.
        s0?.scanMatch(codes)?.takeIf { it.certain }?.let { m ->
            val pkg = m.packages.first()
            val st = s0.stopOf(pkg)
            if (st != null) {
                ui.snack("Already on the route: package ${pkg.id} at stop ${st.optimizedIndex} (ID ${st.entryNo})")
                return st.id
            }
        }
        val existing = s0?.pendingStopAt(address)
        if (s0 != null && existing != null) {
            askAnotherPackage(s0, existing, packageId, codes) { createStop(address, door, typed, approximate, packageId, notes, codes) }
            return null
        }
        // A namesake in another state ("14 East Street" in Pennsylvania) turns a 13-minute route into a 31-hour one: ask first.
        val origin = searchBias()
        val away = if (origin != null) haversine(origin, door) else 0.0
        // Once a far area is already on the route (the driver accepted it), more stops in that vicinity, or still within
        // 70 miles of the start, don't ask again.
        val farOnRoute = if (origin == null) emptyList() else
            s0?.stops.orEmpty().filter { it.status == Status.PENDING && haversine(origin, it.target) > FAR_STOP_M }
        val knownVicinity = farOnRoute.isNotEmpty() && (away <= STILL_OK_M || farOnRoute.any { haversine(it.target, door) <= VICINITY_M })
        if (origin != null && alive && away > FAR_STOP_M && !knownVicinity) {
            MaterialAlertDialogBuilder(act).setTitle("This stop is far away")
                .setMessage("${address.substringBefore(',')} is ${formatDistance(haversine(origin, door).toInt(), imperialUnits())} from your start. " +
                    "Is that the right place? Adding the city or ZIP usually finds the nearby one.")
                .setPositiveButton("Add it anyway") { _, _ -> createStop(address, door, typed, approximate, packageId, notes, codes) }
                .setNegativeButton("Don't add", null).show()
            return null
        }
        return createStop(address, door, typed, approximate, packageId, notes, codes)
    }

    /** "Are you adding another package?" for an address that is already a pending stop. */
    private fun askAnotherPackage(s: RouteSession, existing: Stop, packageId: String?, codes: List<String>, addAsNewStop: () -> Unit) {
        if (!alive) return
        val have = s.finder(existing).size
        MaterialAlertDialogBuilder(act).setTitle("Another package for this address?")
            .setMessage("${existing.address.substringBefore(',')} is already on the route: stop ${existing.optimizedIndex} (ID ${existing.entryNo}), " +
                "total packages: $have.\n\nAre you adding another package for it?")
            .setPositiveButton("Yes, another package") { _, _ ->
                if (packageId != null) s.addPackage(Pkg(packageId, existing.id, codes = codes)) else s.addPackageTo(existing.id, codes)
                SessionStore.commit(act)
                ui.snack("Stop ${existing.optimizedIndex} (ID ${existing.entryNo}): ${totalPackages(s.finder(existing).size)}")
            }
            .setNegativeButton("No, it's a new stop") { _, _ -> addAsNewStop() }
            .setNeutralButton("Cancel", null).show()
    }

    private fun createStop(address: String, door: LatLng, typed: String?, approximate: Boolean, packageId: String?, notes: String, codes: List<String>): String {
        val pin = prefs.addressMemory.access(address) ?: typed?.let { prefs.addressMemory.access(it) }
        // A remembered drop-off pin beats any looked-up position.
        val stop0 = Stop(UUID.randomUUID().toString(), address, door, pin ?: door, notes = notes, approximate = approximate && pin == null)
        val pkgs = listOfNotNull(packageId?.let { Pkg(it, stop0.id) })
        val s = SessionStore.session
        val stop = if (s == null) {
            // A route's first stop goes through addStop too, so it gets its address ID and first package.
            val fresh = newSession(emptyList(), emptyList())
            val added = fresh.addStop(stop0, pkgs, codes)
            SessionStore.set(act, fresh)
            added
        } else {
            s.addStop(stop0, pkgs, codes).also { SessionStore.commit(act) }
        }
        val n = SessionStore.session?.stop(stop.id)?.optimizedIndex ?: 1
        replanSoon()
        ui.snack("Added stop $n (ID ${stop.entryNo})", "Optimize") { replan(optimize = true, showBusy = true) }
        return stop.id
    }

    /**
     * A CSV from the file picker, a share or an "open with": a route with positions (from another phone running this app,
     * or exported from a spreadsheet with lat/lng) is imported as-is after asking how; a plain address list goes through
     * the normal import (look the addresses up, plan a new route).
     */
    fun importRouteFile(uri: Uri) {
        background({
            val text = RouteSharing.read(act, uri)
            if (RouteShare.looksShared(text)) RouteShare.decode(text, LocalTime.now().toSecondOfDay().toLong()) else null
        }) { r ->
            r.onFailure { alert("Couldn't open the file", it.message ?: "The file can't be read.") }
            r.onSuccess { shared -> if (shared != null) askHowToImport(shared) else importCsv(uri) }
        }
    }

    private fun askHowToImport(shared: RouteShare.Shared) {
        if (!alive) return
        val pkgs = shared.packages.size
        val what = "${shared.stops.size} stop" + (if (shared.stops.size == 1) "" else "s") + ", $pkgs package" + (if (pkgs == 1) "" else "s") +
            (if (shared.skipped > 0) "\n(${shared.skipped} unreadable entr${if (shared.skipped == 1) "y was" else "ies were"} left out.)" else "") +
            "\n\nStops keep their positions, so no address lookup is needed."
        val current = SessionStore.session?.takeIf { it.stops.isNotEmpty() }
        val b = MaterialAlertDialogBuilder(act).setTitle("Import this route?").setNegativeButton("Cancel", null)
        if (current == null) b.setMessage(what).setPositiveButton("Import") { _, _ -> applyImport(shared, replace = false) }
        else b.setMessage("$what\n\nYou already have a route open.")
            .setPositiveButton("Add to my route") { _, _ -> applyImport(shared, replace = false) }
            .setNeutralButton("Replace my route") { _, _ -> applyImport(shared, replace = true) }
        b.show()
    }

    private fun applyImport(shared: RouteShare.Shared, replace: Boolean) {
        val open = SessionStore.session
        val target = if (open == null || replace) newSession(emptyList(), emptyList()).also { SessionStore.set(act, it) } else open
        val r = target.importShared(shared)
        SessionStore.commit(act)
        replan(optimize = false, showBusy = false)
        val bits = listOfNotNull(
            "${r.added} stop" + (if (r.added == 1) "" else "s") + " added",
            r.merged.takeIf { it > 0 }?.let { "$it merged into existing stops" },
        )
        ui.snack(bits.joinToString(" · "), if (r.added > 0) "Optimize" else null) { replan(optimize = true, showBusy = true) }
    }

    /** Imports an offline address pack (addresses.sqlite) after validating it. */
    fun importAddressPack(uri: Uri, done: () -> Unit) {
        val dlg = busy("Importing address data…", "Checking the file")
        background({ AddressPack.import(act, uri); AddressPack.summary(act) ?: "" }) { r ->
            dlg.close()
            r.fold({ ui.snack("Address data ready: $it") }, { alert("Address data not imported", it.message ?: it.toString()) })
            done()
        }
    }

    /** Copies a valhalla_tiles.tar into app storage for on-device routing. */
    fun importTiles(uri: Uri) {
        val dlg = busy("Importing map data…")
        background({
            val tmp = File(act.filesDir, "valhalla_tiles.tar.part")
            (act.contentResolver.openInputStream(uri) ?: error("Couldn't read the file")).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val dest = Routing.tilesFile(act)
            check(tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) { "Couldn't move the file into place" }
            File(act.filesDir, "offline-data.json").delete() // a hand-imported file has no published build to compare with
            Routing.source(act, prefs).label
        }) { r ->
            dlg.close()
            ui.snack(r.fold({ "Routing with $it" }, { "Import failed: ${it.message}" }))
        }
    }

    companion object {
        const val REQ_LOCATION = 2
        /** How long adding must pause before the route is re-timed. */
        const val REPLAN_DELAY_MS = 1500L
        /** A new stop farther than this (about 50 miles) from the route start gets a "right place?" question. */
        const val FAR_STOP_M = 80_000.0
        /** With a far stop already accepted, stops within this (70 miles) of the start no longer ask. */
        const val STILL_OK_M = 112_654.0
        /** ...and neither do stops within this (about 25 miles) of an already accepted far stop. */
        const val VICINITY_M = 40_000.0
    }
}
