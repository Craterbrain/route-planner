package app.routeplanner.core

enum class Status { PENDING, COMPLETED, FAILED, SKIPPED }

enum class FailReason { RECIPIENT_MISSING, ACCESS_CODE_NEEDED, WRONG_ADDRESS }

/**
 * A delivery stop. [target] is the door (where the driver walks); [access] is
 * the curb/drop-off point the router aims at. Times are seconds from route start.
 */
data class Stop(
    val id: String,
    val address: String,
    val target: LatLng,
    val access: LatLng = target,
    val serviceSec: Int = 120,
    val priority: Boolean = false,
    val window: LongRange? = null,
    val notes: String = "",
    val status: Status = Status.PENDING,
    val failReason: FailReason? = null,
    val originalIndex: Int = 0,
    val optimizedIndex: Int = 0,
    val etaSec: Int = 0,
    val lateSec: Int = 0,
    val completedAt: Long? = null,
)

data class Pkg(
    val id: String,
    val stopId: String,
    val truckZone: String? = null,
    val status: Status = Status.PENDING,
)
