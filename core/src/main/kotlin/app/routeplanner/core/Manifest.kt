package app.routeplanner.core

import java.util.UUID

/** Minimal RFC-4180-ish CSV parser (quotes, escaped quotes, CRLF). */
fun parseCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    fun endRow() {
        row.add(field.toString()); field.clear()
        if (row.any { it.isNotEmpty() }) rows += row
        row = ArrayList()
    }
    while (i < text.length) {
        val c = text[i]
        when {
            quoted -> when {
                c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = false
                else -> field.append(c)
            }
            c == '"' -> quoted = true
            c == ',' -> { row.add(field.toString()); field.clear() }
            c == '\n' || c == '\r' -> { if (c == '\r' && text.getOrNull(i + 1) == '\n') i++; endRow() }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
}

/** [warnings]: rows imported with an estimated position (adjust the pin). */
class Manifest(val stops: List<Stop>, val packages: List<Pkg>, val errors: List<String>, val warnings: List<String> = emptyList())

private val TRUTHY = Regex("^(1|true|yes|y|first)$", RegexOption.IGNORE_CASE)

/** `priority` column: yes/true/1/first = visit first, "last" = visit last, else optimizer decides. */
fun parsePlacement(v: String): Placement = when {
    TRUTHY.matches(v.trim()) -> Placement.FIRST
    v.trim().equals("last", ignoreCase = true) -> Placement.LAST
    else -> Placement.AUTO
}
private val HHMM = Regex("^(\\d{1,2}):(\\d{2})$")

private fun toSec(s: String): Long? =
    HHMM.matchEntire(s.trim())?.let { it.groupValues[1].toLong() * 3600 + it.groupValues[2].toLong() * 60 }

/**
 * Columns (header row required): address, lat, lng, package, zone, priority,
 * window_start, window_end, service_min, notes. Window times are HH:MM wall
 * clock, made route-relative by [dayStartSec]. Rows sharing an address merge
 * into one stop with several packages. `priority` may be yes/first or last. Rows without lat/lng are resolved with
 * [geocoder] when given. A remembered drop-off in [memory] becomes the stop's
 * access point.
 */
fun importManifest(
    csv: String,
    dayStartSec: Long = 0,
    geocoder: Geocoder? = null,
    memory: AddressMemory? = null,
): Manifest {
    val rows = parseCsv(csv)
    val header = rows.firstOrNull() ?: return Manifest(emptyList(), emptyList(), listOf("empty manifest"))
    val col = header.withIndex().associate { (i, h) -> h.trim().lowercase() to i }
    val errors = ArrayList<String>()
    val warnings = ArrayList<String>()
    val stops = LinkedHashMap<String, Stop>()
    val packages = ArrayList<Pkg>()
    for ((n, r) in rows.drop(1).withIndex()) {
        fun get(k: String) = col[k]?.let { r.getOrNull(it) }?.trim().orEmpty()
        val address = get("address")
        if (address.isEmpty()) { errors += "row ${n + 2}: missing address"; continue }
        val key = normalizeAddress(address)
        var lat = get("lat").toDoubleOrNull()
        var lng = get("lng").toDoubleOrNull()
        var approximate = false
        if ((lat == null || lng == null) && key !in stops) {
            val detailed = (geocoder as? DetailedGeocoder)?.let { runCatching { it.lookup(address) }.getOrNull() }
            val hit = detailed?.point ?: if (geocoder !is DetailedGeocoder) geocoder?.let { runCatching { it.geocode(address) }.getOrNull() } else null
            if (hit == null) {
                errors += "row ${n + 2}: no coordinates" + if (geocoder != null) " and geocoding failed" else " (needs lat/lng)"
                continue
            }
            if (detailed?.approximate == true) {
                approximate = true
                warnings += "row ${n + 2}: estimated position for “$address” (${detailed.source.caption}); adjust the pin"
            }
            lat = hit.lat; lng = hit.lng
        }
        val placement = parsePlacement(get("priority"))
        val existing = stops[key]
        val stop = if (existing == null) {
            val ws = toSec(get("window_start"))
            val we = toSec(get("window_end"))
            val target = LatLng(lat!!, lng!!)
            Stop(
                id = UUID.randomUUID().toString(),
                address = address,
                target = target,
                access = memory?.access(address) ?: target,
                serviceSec = ((get("service_min").toDoubleOrNull() ?: 2.0) * 60).toInt(),
                placement = placement,
                window = if (ws != null && we != null) (ws - dayStartSec)..(we - dayStartSec) else null,
                notes = get("notes"),
                approximate = approximate && memory?.access(address) == null,
            ).also { stops[key] = it }
        } else if (placement != Placement.AUTO && existing.placement == Placement.AUTO) existing.copy(placement = placement).also { stops[key] = it }
        else existing
        packages += Pkg(get("package").ifEmpty { UUID.randomUUID().toString() }, stop.id, get("zone").ifEmpty { null })
    }
    return Manifest(stops.values.toList(), packages, errors, warnings)
}
