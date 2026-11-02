package app.routeplanner.core

/**
 * Driver-side route state machine behind the route screen and action bar:
 * [Prev] (Delivered) [Next], plus edits (move, make next,
 * reverse, remove, first/last). Pure state; persistence and UI live elsewhere.
 *
 * Stop times ([Stop.etaSec], [Stop.window]) are seconds on the route clock,
 * whose zero is [startMs] (epoch millis).
 */
class RouteSession(
    stops: List<Stop>,
    packages: List<Pkg> = emptyList(),
    navApp: NavApp = NavApp.GOOGLE,
    autoNavigate: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
    startMs: Long? = null,
) {
    /**
     * Called after any change to the route's state (stops, packages, the cursor, start/end, names, flags). The app's
     * SessionStore uses it to save and redraw, so a caller that changes the route can't forget to. May fire several
     * times for one action; the listener is expected to coalesce.
     */
    var onChanged: (() -> Unit)? = null
    private fun changed() { onChanged?.invoke() }

    var stops: List<Stop> = stops
        private set(v) { field = v; changed() }
    var packages: List<Pkg> = packages
        private set(v) { field = v; changed() }
    var cursor: Int = stops.indexOfFirst { it.status == Status.PENDING }.coerceAtLeast(0)
        private set(v) { field = v; changed() }

    var navApp: NavApp = navApp
        set(v) { if (field != v) { field = v; changed() } }
    var autoNavigate: Boolean = autoNavigate
        set(v) { if (field != v) { field = v; changed() } }

    /** Highest address ID handed out so far (IDs are never reused, even after a removal). */
    private var entrySeq = 0

    init {
        // Routes from before address IDs existed get them now, in their current order.
        entrySeq = this.stops.maxOfOrNull { it.entryNo } ?: 0
        if (this.stops.any { it.entryNo == 0 }) this.stops = this.stops.map { if (it.entryNo == 0) it.copy(entryNo = ++entrySeq) else it }
    }

    /** Name this route was saved under, and the library entry it came from / was saved to (null = never saved). */
    var name: String? = null
        set(v) { if (field != v) { field = v; changed() } }
    var savedId: String? = null
        set(v) { if (field != v) { field = v; changed() } }

    /** Epoch millis of route-clock zero. */
    var startMs: Long = startMs ?: now()
        set(v) { if (field != v) { field = v; changed() } }

    /** Driver tapped "Start route": the next-stop card replaces the planning controls. */
    var started: Boolean = false
        set(v) { if (field != v) { field = v; changed() } }

    /** Stops were added/moved/edited since the last optimize, so ETAs are stale. */
    var dirty: Boolean = false
        set(v) { if (field != v) { field = v; if (v) optimized = false; changed() } }

    /**
     * True from an Optimize (or an import, which optimizes) until the route is edited again. The map draws the route line
     * only while this holds: a line through stops in an order nobody has chosen or optimized is misleading.
     */
    var optimized: Boolean = false
        set(v) { if (field != v) { field = v; changed() } }

    /** Route start (depot). Required before planning; null only for routes saved by old versions. */
    var start: RouteStart? = null
        set(v) { if (field != v) { field = v; changed() } }

    /** Where the route ends (default: at the last stop). */
    var end: RouteEnd = RouteEnd()
        set(v) { if (field != v) { field = v; changed() } }

    /** Final leg to the end point from the last plan (0 when the route ends at the last stop). */
    var endLegSec: Int = 0
        set(v) { if (field != v) { field = v; changed() } }
    var endLegMeters: Int = 0
        set(v) { if (field != v) { field = v; changed() } }

    /** End point for planning, or null when the route just ends at the last stop. */
    fun endPoint(): LatLng? = when (end.mode) {
        EndMode.LAST_STOP -> null
        EndMode.ROUND_TRIP -> start?.point
        EndMode.ADDRESS -> end.point
    }

    /**
     * Where (re-)planning starts. Before the driver has finished any stop the
     * depot is the route start; once under way it is the driver's current
     * position [driver], else the last finished stop, else the start.
     */
    fun planOrigin(driver: LatLng?): LatLng? {
        val underway = started && stops.any { it.status != Status.PENDING }
        return if (!underway) start?.point ?: driver
        else driver ?: stops.lastOrNull { it.status != Status.PENDING }?.access ?: start?.point
    }

    val current: Stop? get() = stops.getOrNull(cursor)
    val done: Boolean get() = stops.all { it.status != Status.PENDING }

    /** Seconds on the route clock at epoch millis [ms]. */
    fun clockSec(ms: Long = now()): Int = ((ms - startMs) / 1000).toInt()

    /** Package Finder card: what to grab for the stop and where it is on the truck. */
    fun finder(stop: Stop? = current): List<Pkg> = stop?.let { s -> packages.filter { it.stopId == s.id } }.orEmpty()

    /** Deep link for the stop, aimed at the street access point. */
    fun link(stop: Stop? = current): String? = stop?.let { navLink(navApp, it.access) }

    fun stop(id: String): Stop? = stops.firstOrNull { it.id == id }
    private fun indexOf(id: String) = stops.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    private fun firstPending(from: Int = 0): Int? =
        (from until stops.size).firstOrNull { stops[it].status == Status.PENDING }
            ?: stops.indexOfFirst { it.status == Status.PENDING }.takeIf { it >= 0 }

    /** Renumbers stops in list (route) order. */
    private fun renumber(l: List<Stop>) = l.mapIndexed { i, s -> if (s.optimizedIndex == i + 1) s else s.copy(optimizedIndex = i + 1) }

    private fun keepCursorOn(id: String?) {
        cursor = id?.let { indexOf(it) } ?: firstPending() ?: 0
    }

    private fun set(i: Int, status: Status, reason: FailReason? = null, note: String? = null, photo: String? = null, signature: String? = null) {
        val s = stops[i]
        stops = stops.toMutableList().also {
            it[i] = s.copy(
                status = status,
                failReason = reason,
                completedAt = if (status == Status.PENDING) null else now(),
                proofNote = note?.takeIf { it.isNotBlank() },
                proofPhoto = photo,
                proofSignature = signature,
            )
        }
        packages = packages.map { if (it.stopId == s.id) it.copy(status = status) else it }
    }

    /** Moves to the next pending stop (wrapping). Returns a link to launch if auto-navigate is on. */
    private fun advance(): String? {
        firstPending(cursor + 1)?.let { cursor = it }
        return if (autoNavigate && !done) link() else null
    }

    /**
     * Sets [id]'s outcome. When it is the current stop the cursor advances and
     * the auto-navigate link (if any) is returned; other stops (e.g. swiped in
     * the list) leave the cursor alone.
     */
    fun mark(id: String, status: Status, reason: FailReason? = null, note: String? = null, photo: String? = null, signature: String? = null): String? {
        val i = indexOf(id) ?: return null
        set(i, status, reason, note, photo, signature)
        if (status == Status.PENDING) return null
        return if (i == cursor) advance() else null
    }

    fun deliver(note: String? = null, photo: String? = null, signature: String? = null): String? =
        current?.let { mark(it.id, Status.COMPLETED, note = note, photo = photo, signature = signature) }
    fun fail(reason: FailReason): String? = current?.let { mark(it.id, Status.FAILED, reason) }
    fun skip(): String? = current?.let { mark(it.id, Status.SKIPPED) }

    /** Reverts a finished stop to pending ("Undo"). */
    fun undo(id: String) { mark(id, Status.PENDING) }

    /** For deserialization: put the cursor back where the driver left it. */
    fun restoreCursor(i: Int) { cursor = i.coerceIn(0, (stops.size - 1).coerceAtLeast(0)) }

    /** Driver taps a stop in the list to work on it now. */
    fun jumpTo(id: String) { indexOf(id)?.let { cursor = it } }

    fun next() { if (cursor < stops.size - 1) cursor++ }

    /** Looks at the previous stop without changing any status (the in-app card's back arrow). */
    fun back() { if (cursor > 0) cursor-- }

    /** Reverts the previous stop to pending and refocuses on it. */
    fun prev() {
        if (cursor == 0) return
        cursor--
        if (stops[cursor].status != Status.PENDING) set(cursor, Status.PENDING)
    }

    /** "Start route": highlights the first pending stop. */
    fun start() {
        started = true
        firstPending()?.let { cursor = it }
    }

    /** "Stop": leave driving mode and go back to planning. Every status, proof and the position in the list are kept. */
    fun stop() { started = false }

    // ---- Edits -------------------------------------------------------------

    /** Drag-to-reorder / "Move to position": [to] is the 0-based list index. */
    fun moveStop(id: String, to: Int): Boolean {
        val from = indexOf(id) ?: return false
        val cur = current?.id
        val l = stops.toMutableList()
        val s = l.removeAt(from)
        l.add(to.coerceIn(0, l.size), s)
        stops = renumber(l)
        keepCursorOn(cur)
        if (from != to) dirty = true
        return true
    }

    /** "Make next": the pending stop [id] is visited right after the current one. */
    fun makeNext(id: String): Boolean {
        val s = stop(id) ?: return false
        if (s.status != Status.PENDING) return false
        val cur = current
        if (cur?.id == id) return true
        val rest = stops.filter { it.id != id }
        val at = if (cur != null && cur.status == Status.PENDING) rest.indexOfFirst { it.id == cur.id } + 1
        else rest.indexOfFirst { it.status == Status.PENDING }.let { if (it < 0) rest.size else it }
        stops = renumber(rest.toMutableList().also { it.add(at, s) })
        keepCursorOn(if (cur != null && cur.status == Status.PENDING) cur.id else id)
        dirty = true
        return true
    }

    /** Reverses the order of the pending stops; finished stops stay where they are. */
    fun reverse() {
        val cur = current?.id
        val wasPending = current?.status == Status.PENDING
        val pendingSlots = stops.indices.filter { stops[it].status == Status.PENDING }
        if (pendingSlots.size < 2) return
        val reversed = pendingSlots.map { stops[it] }.reversed()
        val l = stops.toMutableList()
        pendingSlots.forEachIndexed { k, slot -> l[slot] = reversed[k] }
        stops = renumber(l)
        // After a reverse the driver starts at the new first pending stop.
        if (wasPending) cursor = firstPending() ?: 0 else keepCursorOn(cur)
        dirty = true
    }

    /** Removes a stop and its packages. */
    fun removeStop(id: String): Boolean {
        val i = indexOf(id) ?: return false
        val cur = current?.id
        stops = renumber(stops.filter { it.id != id })
        packages = packages.filter { it.stopId != id }
        if (cur != id) keepCursorOn(cur)
        else cursor = firstPending(i.coerceAtMost(stops.size)) ?: (i - 1).coerceAtLeast(0)
        if (stops.isEmpty()) cursor = 0
        dirty = true
        return true
    }

    /** Edits a stop in place (notes, first/last, window, time at stop, ...). Route-affecting edits mark [dirty]. */
    fun edit(id: String, f: (Stop) -> Stop): Stop? {
        val i = indexOf(id) ?: return null
        val old = stops[i]
        val new = f(old).copy(id = old.id)
        stops = stops.toMutableList().also { it[i] = new }
        if (new.placement != old.placement || new.window != old.window || new.serviceSec != old.serviceSec || new.access != old.access) dirty = true
        return new
    }

    /** Driver moved the drop-off pin of [id]. Returns the stop so the caller can persist per-address. */
    fun setAccess(id: String, p: LatLng): Stop? = edit(id) { it.copy(access = p) }

    /**
     * The driver says where an estimated stop really is: the door and the drop-off both move there and the
     * "estimated position" flag is cleared. A stop that wasn't estimated only gets a new drop-off point.
     */
    fun placeStop(id: String, p: LatLng): Stop? =
        edit(id) { if (it.approximate) it.copy(target = p, access = p, approximate = false) else it.copy(access = p) }

    /** Driver moved the drop-off pin of the current stop. */
    fun adjustAccessPoint(p: LatLng): Stop? = current?.let { setAccess(it.id, p) }

    /** Package finder: where on the truck a package is. */
    fun setZone(pkgId: String, zone: String?) {
        packages = packages.map { if (it.id == pkgId) it.copy(truckZone = zone?.trim()?.ifEmpty { null }) else it }
    }

    /** Attaches another package to an existing stop (a second parcel for the same address). */
    fun addPackage(pkg: Pkg) {
        if (packages.none { it.id == pkg.id }) packages = packages + pkg
    }

    /** A stop at this address that is still to be done, if any (spacing and case ignored). */
    fun pendingStopAt(address: String): Stop? {
        val key = normalizeAddress(address)
        return stops.firstOrNull { it.status == Status.PENDING && (normalizeAddress(it.address) == key || AddressMatch.same(it.address, address)) }
    }

    /** Package finder lookup by one scanned code: the package and the stop it belongs to. */
    fun findPackage(code: String): Pair<Pkg, Stop>? = scanMatch(listOf(code))?.takeIf { it.packages.size == 1 }?.let { m ->
        val p = m.packages.first()
        indexOf(p.stopId)?.let { p to stops[it] }
    }

    /** Which package(s) a scan (all the values read from one parcel) points to, or null. See [Barcodes.find]. */
    fun scanMatch(scanned: Collection<String>): Barcodes.Match? = Barcodes.find(packages, scanned)

    /** The stop a package sits at. */
    fun stopOf(pkg: Pkg): Stop? = indexOf(pkg.stopId)?.let { stops[it] }

    /** What the "Added stop" banner describes: the stop and the package last added (not saved; cleared by the next plan). */
    var lastAddedStopId: String? = null
    var lastAddedPkgId: String? = null

    /** Appends a stop without re-planning (add first, then optimize or update). */
    fun addStop(stop0: Stop, newPackages: List<Pkg> = emptyList(), codes: List<String> = emptyList()): Stop {
        val hadPending = stops.any { it.status == Status.PENDING }
        // The next address ID, unless the caller brought a free one (an import numbers rows in file order).
        val stop = if (stop0.entryNo > 0 && stops.none { it.entryNo == stop0.entryNo }) stop0 else stop0.copy(entryNo = entrySeq + 1)
        entrySeq = maxOf(entrySeq, stop.entryNo)
        stops = renumber(stops + stop)
        // Every address holds at least one package; a typed/scanned address gets "<ID>-1".
        packages = packages + newPackages + listOfNotNull(Pkg("${stop.entryNo}-1", stop.id, codes = codes).takeIf { newPackages.none { p -> p.stopId == stop.id } })
        if (!hadPending) cursor = stops.size - 1
        lastAddedStopId = stop.id
        lastAddedPkgId = packages.lastOrNull { it.stopId == stop.id }?.id
        dirty = true
        return stop
    }

    /** What [importShared] did. */
    class Imported(val added: Int, val merged: Int, val packagesAdded: Int)

    /**
     * Adds a route received from another phone. A stop whose address is already pending here is not duplicated: its
     * packages are added to the existing stop (unless that parcel's code is already here). Other stops are added with
     * their own address ID when it is free here, else the next one; packages keep a custom id (a tracking number) when
     * it is free, and the automatic "<ID>-<n>" ids follow this phone's numbering. Marks the route dirty: re-plan after.
     */
    fun importShared(shared: RouteShare.Shared): Imported {
        var added = 0; var merged = 0; var pkgCount = 0
        val auto = Regex("""\d+-\d+""")
        for (incoming in shared.stops) {
            val pkgs = shared.packages.filter { it.stopId == incoming.id }
            val existing = pendingStopAt(incoming.address)
            if (existing != null) {
                var any = false
                for (p in pkgs) {
                    val long = p.codes.filter { Barcodes.clean(it).length >= Barcodes.LONG } + listOf(p.id).filter { !auto.matches(it) }
                    if (long.isNotEmpty() && Barcodes.find(packages, long)?.certain == true) continue // that parcel is already here
                    val made = addPackageTo(existing.id, p.codes)
                    if (made != null) { setZone(made.id, p.truckZone); pkgCount++; any = true }
                }
                if (any || pkgs.isEmpty()) merged++
                continue
            }
            val custom = pkgs.filter { !auto.matches(it.id) && packages.none { q -> q.id == it.id } }
                .map { Pkg(it.id, incoming.id, it.truckZone, codes = it.codes) }
            val autoPkgs = pkgs.filter { p -> custom.none { it.id == p.id } }
            val st = addStop(incoming, custom, codes = if (custom.isEmpty()) autoPkgs.firstOrNull()?.codes.orEmpty() else emptyList())
            if (custom.isEmpty() && autoPkgs.isNotEmpty()) setZone("${st.entryNo}-1", autoPkgs[0].truckZone)
            for (a in if (custom.isEmpty()) autoPkgs.drop(1) else autoPkgs) addPackageTo(st.id, a.codes)?.let { setZone(it.id, a.truckZone) }
            added++
            pkgCount += maxOf(1, pkgs.size)
        }
        dirty = true
        return Imported(added, merged, pkgCount)
    }

    /** Another parcel for the same address: package "<ID>-<n>" on [stopId]. */
    fun addPackageTo(stopId: String, codes: List<String> = emptyList()): Pkg? {
        val st = indexOf(stopId)?.let { stops[it] } ?: return null
        var n = packages.count { it.stopId == stopId } + 1
        while (packages.any { it.id == "${st.entryNo}-$n" }) n++
        return Pkg("${st.entryNo}-$n", stopId, codes = codes).also { packages = packages + it; lastAddedStopId = stopId; lastAddedPkgId = it.id }
    }

    // ---- Re-planning -------------------------------------------------------

    /**
     * Plans the pending stops (plus [extra]) from [from] at [nowMs]: optimized
     * order when [optimize], otherwise the current order with fresh ETAs. Reads
     * a snapshot only, so it can run off the UI thread; apply with [applyPlan].
     */
    fun planRemaining(provider: MatrixProvider, from: LatLng, nowMs: Long = now(), optimize: Boolean = true, extra: List<Stop> = emptyList()): Solver.Result {
        val pending = stops.filter { it.status == Status.PENDING } + extra
        val t0 = clockSec(nowMs).coerceAtLeast(0)
        val end = endPoint()
        return if (optimize) Solver.optimize(from, pending, provider, t0, end) else Solver.schedule(from, pending, provider, t0, end)
    }

    /**
     * Applies a plan from [planRemaining]. Tolerates the session changing while
     * the plan was computed: stops finished meanwhile stay finished, removed
     * stops stay removed (unless listed in [added]), and pending stops the plan
     * doesn't know keep their relative order at the end. With [keepCursor]
     * the driver stays on the stop they were working on (if still pending);
     * otherwise the cursor goes to the first pending stop.
     */
    fun applyPlan(plan: Solver.Result, added: List<Stop> = emptyList(), newPackages: List<Pkg> = emptyList(), keepCursor: Boolean = false) {
        val curId = current?.takeIf { it.status == Status.PENDING }?.id
        val byId = stops.associateBy { it.id }
        val addedIds = added.map { it.id }.toSet()
        val finished = stops.filter { it.status != Status.PENDING }
        val planned = plan.stops.mapNotNull { p ->
            val cur = byId[p.id]
            when {
                cur == null -> p.takeIf { it.id in addedIds }
                cur.status != Status.PENDING -> null
                else -> cur.copy(etaSec = p.etaSec, lateSec = p.lateSec, legMeters = p.legMeters, originalIndex = p.originalIndex)
            }
        }
        val plannedIds = planned.map { it.id }.toSet()
        val leftovers = stops.filter { it.status == Status.PENDING && it.id !in plannedIds }
        stops = renumber(finished + planned + leftovers)
        val known = packages.map { it.id }.toSet()
        packages = packages + newPackages.filter { it.id !in known }
        cursor = curId?.takeIf { keepCursor }?.let { indexOf(it) } ?: firstPending() ?: 0
        endLegSec = plan.endLegSec
        endLegMeters = plan.endLegMeters
        dirty = false
        lastAddedStopId = null; lastAddedPkgId = null
        // A plan built by optimizing (cursor reset to the first stop) makes the order a chosen one; a plain re-timing does not.
        if (!keepCursor) optimized = true
    }

    /** Live injection: re-optimizes only pending stops (plus [extra]) from [from]; finished stops are kept. */
    fun inject(extra: Stop, provider: MatrixProvider, from: LatLng, newPackages: List<Pkg> = emptyList()): Solver.Result {
        val res = planRemaining(provider, from, extra = listOf(extra))
        applyPlan(res, listOf(extra), newPackages)
        return res
    }

    /** Route summary bar figures at [nowMs]. */
    fun summary(nowMs: Long = now()): RouteSummary = RouteSummary.of(stops, clockSec(nowMs), endLegSec, endLegMeters)
}
