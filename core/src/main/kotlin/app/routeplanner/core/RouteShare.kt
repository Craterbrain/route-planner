package app.routeplanner.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A route handed from one phone to another as a CSV file: the same format the app already imports (and that a
 * spreadsheet opens), with a few extra columns. One row per package; rows with the same address are one stop.
 * Every row carries its coordinates, so the receiving phone does no geocoding and works offline.
 *
 * Columns: address, lat, lng, access_lat, access_lng, id, package, zone, codes, priority, window_start, window_end,
 * service_min, notes, approximate. Not shared: the sender's start/end, delivery status, ETAs, proof photos and signatures.
 */
object RouteShare {
    const val MIME = "text/csv"
    const val EXTENSION = "csv"
    private const val MAX_STOPS = 2000

    val COLUMNS = listOf(
        "address", "lat", "lng", "access_lat", "access_lng", "id", "package", "zone", "codes", "priority",
        "window_start", "window_end", "service_min", "notes", "approximate",
    )

    /** What [decode] reads. [skipped] = rows that could not be used. Stop ids are fresh; status is pending. */
    class Shared(val stops: List<Stop>, val packages: List<Pkg>, val skipped: Int)

    /**
     * The file's text. By default only the stops still to do (a hand-off); [includeFinished] copies the whole list.
     * Time windows are written as wall-clock HH:MM (the route clock's zero is [startMs]).
     */
    fun encode(stops: List<Stop>, packages: List<Pkg>, startMs: Long, includeFinished: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): String {
        val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        fun clock(sec: Long) = hhmm.format(Instant.ofEpochMilli(startMs + sec * 1000))
        val sb = StringBuilder(COLUMNS.joinToString(",")).append("\r\n")
        for (s in stops.filter { includeFinished || it.status == Status.PENDING }) {
            val pk = packages.filter { it.stopId == s.id }.ifEmpty { listOf(null) }
            for (p in pk) {
                val row = listOf(
                    s.address, s.target.lat.toString(), s.target.lng.toString(),
                    if (s.access != s.target) s.access.lat.toString() else "", if (s.access != s.target) s.access.lng.toString() else "",
                    s.entryNo.toString(), p?.id.orEmpty(), p?.truckZone.orEmpty(), p?.codes?.joinToString(";").orEmpty(),
                    when (s.placement) { Placement.FIRST -> "first"; Placement.LAST -> "last"; else -> "" },
                    s.window?.let { clock(it.first) }.orEmpty(), s.window?.let { clock(it.last) }.orEmpty(),
                    if (s.serviceSec != 120) (s.serviceSec / 60.0).toString().removeSuffix(".0") else "",
                    s.notes, if (s.approximate) "yes" else "",
                )
                sb.append(row.joinToString(",") { DeliveryReport.field(it) }).append("\r\n")
            }
        }
        return sb.toString()
    }

    /** True when [csv] is a route with positions (address, lat and lng columns) rather than a plain address list to look up. */
    fun looksShared(csv: String): Boolean {
        val header = parseCsv(csv.lineSequence().take(1).joinToString("\n")).firstOrNull()?.map { it.trim().lowercase() } ?: return false
        return "address" in header && "lat" in header && "lng" in header
    }

    /** Reads a shared route; throws [IllegalArgumentException] with a message fit for the user if it has nothing usable. */
    fun decode(csv: String, dayStartSec: Long = 0): Shared {
        if (!looksShared(csv)) throw IllegalArgumentException("This isn't a route with positions (it needs address, lat and lng columns).")
        val m = importManifest(csv, dayStartSec)
        if (m.stops.size > MAX_STOPS) throw IllegalArgumentException("The file has too many stops (${m.stops.size}).")
        if (m.stops.isEmpty()) throw IllegalArgumentException("The file has no readable stops.")
        return Shared(m.stops, m.packages, m.errors.size)
    }

    /** Plain-text list that goes along with the file, readable in any messaging app. */
    fun summaryText(stops: List<Stop>, packages: List<Pkg>, includeFinished: Boolean = false): String {
        val chosen = stops.filter { includeFinished || it.status == Status.PENDING }
        if (chosen.isEmpty()) return "No stops."
        return buildString {
            append("Route: ${chosen.size} stop" + (if (chosen.size == 1) "" else "s") + "\n")
            for (s in chosen) {
                val n = packages.count { it.stopId == s.id }
                append("${s.optimizedIndex}. ${s.address}  (ID ${s.entryNo}, packages: $n)")
                if (s.notes.isNotBlank()) append(" - ${s.notes}")
                append('\n')
            }
            append("Open the attached file with Route Planner to import it.")
        }
    }
}
