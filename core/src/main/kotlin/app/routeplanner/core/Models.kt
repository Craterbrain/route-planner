package app.routeplanner.core

enum class Status { PENDING, COMPLETED, FAILED, SKIPPED }

/**
 * Why a stop failed. The first three are kept for routes saved by older
 * versions.
 */
enum class FailReason { RECIPIENT_MISSING, ACCESS_CODE_NEEDED, WRONG_ADDRESS, NO_PARKING, NO_TIME, OTHER }

/** A stop's "Order" setting: visit first, let the optimizer decide, or visit last. */
enum class Placement { FIRST, AUTO, LAST }

/**
 * A delivery stop. [target] is the door (where the driver walks); [access] is
 * the curb/drop-off point the router aims at. Times are seconds from the
 * session's route start ([RouteSession.startMs]).
 */
data class Stop(
    val id: String,
    val address: String,
    val target: LatLng,
    val access: LatLng = target,
    val serviceSec: Int = 120,
    val placement: Placement = Placement.AUTO,
    val window: LongRange? = null,
    val notes: String = "",
    val status: Status = Status.PENDING,
    val failReason: FailReason? = null,
    val originalIndex: Int = 0,
    val optimizedIndex: Int = 0,
    val etaSec: Int = 0,
    val lateSec: Int = 0,
    /** Road distance of the leg that arrives here, in meters (0 when unknown). */
    val legMeters: Int = 0,
    val completedAt: Long? = null,
    /** Proof of delivery: free-text note and/or a photo file path. */
    val proofNote: String? = null,
    val proofPhoto: String? = null,
    /** Recipient signature: path of a PNG. */
    val proofSignature: String? = null,
    /**
     * The address's ID number: 1, 2, 3... in the order addresses were put into the app. Never changes when the route
     * is re-optimized or reordered, and is not reused after a stop is removed. 0 = not yet assigned.
     */
    val entryNo: Int = 0,
    /** Position is estimated (interpolated / street-level / approximate geocode): suggest adjusting the pin. */
    val approximate: Boolean = false,
) {
    /** "Do first" stop (the original single priority flag). */
    val priority: Boolean get() = placement == Placement.FIRST
}

data class Pkg(
    val id: String,
    val stopId: String,
    val truckZone: String? = null,
    val status: Status = Status.PENDING,
    /** Everything the camera read from this parcel's label (tracking barcode, DataMatrix, ...): used to recognise the parcel again. */
    val codes: List<String> = emptyList(),
)

/**
 * Where the route begins (the depot, matrix index 0). [live] = "my location":
 * [point] is the last fix, refreshed by the app; otherwise a fixed address or map pin.
 */
data class RouteStart(val point: LatLng, val label: String, val live: Boolean = false, val fixAtMs: Long? = null, val accuracyM: Float? = null)

/** Where the route ends: at the last stop (no return leg), back at the start, or at a chosen point. */
enum class EndMode { LAST_STOP, ROUND_TRIP, ADDRESS }

data class RouteEnd(val mode: EndMode = EndMode.LAST_STOP, val point: LatLng? = null, val label: String? = null)
