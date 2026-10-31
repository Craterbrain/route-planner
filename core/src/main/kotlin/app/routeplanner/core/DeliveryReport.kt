package app.routeplanner.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everything the driver recorded for one stop, ready to hand to a customer or dispatcher. */
object DeliveryReport {
    val COLUMNS = listOf(
        "stop", "id", "address", "status", "time", "failure_reason", "packages", "note", "photo", "signature", "notes_for_stop",
    )

    private val FAIL_TEXT = mapOf(
        FailReason.RECIPIENT_MISSING to "Recipient missing",
        FailReason.ACCESS_CODE_NEEDED to "Access code needed",
        FailReason.WRONG_ADDRESS to "Wrong address",
    )

    /** RFC 4180 field: quoted when it holds a comma, quote or line break. */
    fun field(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /**
     * One row per finished stop, in route order. [fileName] maps a stored file path to the name it
     * will have in an exported bundle (so the CSV points at files that actually ship with it).
     */
    fun csv(
        stops: List<Stop>,
        packages: List<Pkg>,
        zone: ZoneId = ZoneId.systemDefault(),
        fileName: (String) -> String = { it.substringAfterLast('/') },
    ): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder(COLUMNS.joinToString(",")).append("\r\n")
        for (s in stops) {
            if (s.status == Status.PENDING) continue
            val pk = packages.filter { it.stopId == s.id }.joinToString("; ") { it.id }
            val row = listOf(
                s.optimizedIndex.toString(),
                s.entryNo.toString(),
                s.address,
                s.status.name.lowercase(),
                s.completedAt?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: "",
                s.failReason?.let { FAIL_TEXT[it] ?: it.name.lowercase() } ?: "",
                pk,
                s.proofNote ?: "",
                s.proofPhoto?.let(fileName) ?: "",
                s.proofSignature?.let(fileName) ?: "",
                s.notes,
            )
            sb.append(row.joinToString(",") { field(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** Counts for the share message. */
    fun summary(stops: List<Stop>): String {
        val done = stops.count { it.status == Status.COMPLETED }
        val failed = stops.count { it.status == Status.FAILED }
        val skipped = stops.count { it.status == Status.SKIPPED }
        return "$done delivered, $failed failed, $skipped skipped, ${stops.size - done - failed - skipped} pending"
    }
}
