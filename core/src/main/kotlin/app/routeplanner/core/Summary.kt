package app.routeplanner.core

import kotlin.math.roundToInt

/**
 * Figures for the route summary bar ("12 stops · 2 h 14 min · 38 km · done 14:32").
 * Times are seconds on the route clock. [driftSec] is how far behind the plan
 * the driver is now; remaining ETAs and the finish time are pushed back by it.
 */
data class RouteSummary(
    val total: Int,
    val delivered: Int,
    val failed: Int,
    val pending: Int,
    val totalMeters: Int,
    val remainingMeters: Int,
    val remainingSec: Int,
    val finishSec: Int,
    val driftSec: Int,
    val lateStops: Int,
) {
    /** Projected arrival of [s] on the route clock. */
    fun projectedEta(s: Stop): Int = if (s.status == Status.PENDING) s.etaSec + driftSec else s.etaSec

    companion object {
        fun of(stops: List<Stop>, nowSec: Int): RouteSummary {
            val pending = stops.filter { it.status == Status.PENDING }
            val drift = pending.firstOrNull()?.let { maxOf(0, nowSec - it.etaSec) } ?: 0
            val finish = pending.lastOrNull()?.let { it.etaSec + it.serviceSec + drift }
                ?: stops.mapNotNull { s -> s.takeIf { it.status != Status.PENDING }?.let { it.etaSec + it.serviceSec } }.maxOrNull()
                ?: nowSec
            return RouteSummary(
                total = stops.size,
                delivered = stops.count { it.status == Status.COMPLETED },
                failed = stops.count { it.status == Status.FAILED || it.status == Status.SKIPPED },
                pending = pending.size,
                totalMeters = stops.sumOf { it.legMeters },
                remainingMeters = pending.sumOf { it.legMeters },
                remainingSec = if (pending.isEmpty()) 0 else maxOf(0, finish - nowSec),
                finishSec = finish,
                driftSec = drift,
                lateStops = pending.count { s -> s.window?.let { s.etaSec + drift > it.last } ?: false },
            )
        }
    }
}

/** "<1 min", "45 min", "2 h 05 min". */
fun formatDuration(sec: Int): String {
    val min = (sec / 60.0).roundToInt()
    return when {
        sec < 60 -> "<1 min"
        min < 60 -> "$min min"
        else -> "%d h %02d min".format(min / 60, min % 60)
    }
}

/** "850 m" / "12.4 km", or "0.5 mi" / "12.4 mi" when [imperial]. */
fun formatDistance(meters: Int, imperial: Boolean): String = if (imperial) {
    "%.1f mi".format(java.util.Locale.ROOT, meters / 1609.344)
} else if (meters < 1000) "$meters m" else "%.1f km".format(java.util.Locale.ROOT, meters / 1000.0)

/** Splits "100 Main St, Springfield, IL" into ("100 Main St", "Springfield, IL") for two-line rows. */
fun splitAddress(a: String): Pair<String, String> {
    val i = a.indexOf(',')
    return if (i < 0) a.trim() to "" else a.substring(0, i).trim() to a.substring(i + 1).trim()
}
