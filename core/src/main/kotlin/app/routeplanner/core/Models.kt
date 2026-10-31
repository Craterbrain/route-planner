package app.routeplanner.core

enum class Status { PENDING, COMPLETED, FAILED, SKIPPED }

/**
 * Why a stop failed. The first three are kept for routes saved by older
 * versions; the rest follow Circuit's failure list.
 */
enum class FailReason { RECIPIENT_MISSING, ACCESS_CODE_NEEDED, WRONG_ADDRESS, NO_PARKING, NO_TIME, OTHER }

/** Circuit's per-stop "Order" setting: visit first, let the optimizer decide, or visit last. */
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
) {
    /** "Do first" stop (the original single priority flag). */
    val priority: Boolean get() = placement == Placement.FIRST
}

data class Pkg(
    val id: String,
    val stopId: String,
    val truckZone: String? = null,
    val status: Status = Status.PENDING,
)
