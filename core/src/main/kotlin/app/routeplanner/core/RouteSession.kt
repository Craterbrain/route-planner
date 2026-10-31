package app.routeplanner.core

/**
 * Driver-side route state machine behind the action bar:
 * [Prev] (Delivered) [Next]. Pure state; persistence and UI live elsewhere.
 */
class RouteSession(
    stops: List<Stop>,
    packages: List<Pkg> = emptyList(),
    var navApp: NavApp = NavApp.GOOGLE,
    var autoNavigate: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var stops: List<Stop> = stops
        private set
    var packages: List<Pkg> = packages
        private set
    var cursor: Int = stops.indexOfFirst { it.status == Status.PENDING }.coerceAtLeast(0)
        private set

    val current: Stop? get() = stops.getOrNull(cursor)
    val done: Boolean get() = stops.all { it.status != Status.PENDING }

    /** Package Finder card: what to grab for the current stop and where it is on the truck. */
    fun finder(stop: Stop? = current): List<Pkg> = stop?.let { s -> packages.filter { it.stopId == s.id } }.orEmpty()

    /** Deep link for the current stop, aimed at the street access point. */
    fun link(stop: Stop? = current): String? = stop?.let { navLink(navApp, it.access) }

    private fun set(i: Int, status: Status, reason: FailReason? = null) {
        val s = stops[i]
        stops = stops.toMutableList().also {
            it[i] = s.copy(
                status = status,
                failReason = reason,
                completedAt = if (status == Status.COMPLETED) now() else null,
            )
        }
        packages = packages.map { if (it.stopId == s.id) it.copy(status = status) else it }
    }

    /** Moves to the next pending stop (wrapping). Returns a link to launch if auto-navigate is on. */
    private fun advance(): String? {
        val next = (cursor + 1 until stops.size).firstOrNull { stops[it].status == Status.PENDING }
            ?: stops.indexOfFirst { it.status == Status.PENDING }
        if (next != -1) cursor = next
        return if (autoNavigate && !done) link() else null
    }

    fun deliver(): String? { if (current == null) return null; set(cursor, Status.COMPLETED); return advance() }
    fun fail(reason: FailReason): String? { if (current == null) return null; set(cursor, Status.FAILED, reason); return advance() }
    fun skip(): String? { if (current == null) return null; set(cursor, Status.SKIPPED); return advance() }

    /** For deserialization: put the cursor back where the driver left it. */
    fun restoreCursor(i: Int) { cursor = i.coerceIn(0, (stops.size - 1).coerceAtLeast(0)) }

    /** Driver taps a stop in the list to work on it now. */
    fun jumpTo(id: String) { stops.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { cursor = it } }

    fun next() { if (cursor < stops.size - 1) cursor++ }

    /** Reverts the previous stop to pending and refocuses on it. */
    fun prev() {
        if (cursor == 0) return
        cursor--
        if (stops[cursor].status != Status.PENDING) set(cursor, Status.PENDING)
    }

    /** Driver moved the drop-off pin. Returns the stop so the caller can persist per-address. */
    fun adjustAccessPoint(p: LatLng): Stop? {
        val i = cursor.takeIf { current != null } ?: return null
        stops = stops.toMutableList().also { it[i] = it[i].copy(access = p) }
        return stops[i]
    }

    /** Live injection: re-optimizes only pending stops (plus [extra]) from [from]; finished stops are kept. */
    fun inject(extra: Stop, provider: MatrixProvider, from: LatLng, newPackages: List<Pkg> = emptyList()): Solver.Result {
        val finished = stops.filter { it.status != Status.PENDING }
        val res = Solver.optimize(from, stops.filter { it.status == Status.PENDING } + extra, provider)
        stops = finished + res.stops
        packages = packages + newPackages
        cursor = finished.size
        return res
    }
}
