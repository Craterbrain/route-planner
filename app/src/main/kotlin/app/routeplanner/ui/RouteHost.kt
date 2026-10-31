package app.routeplanner.ui

/** What the route screen's pieces (sheet, list, stop details) ask the activity to do. */
interface RouteHost {
    val mapAvailable: Boolean
    fun openStop(id: String)
    fun navigate(stopId: String)
    fun deliver(stopId: String)
    fun deliverWithProof(stopId: String)
    fun fail(stopId: String)
    fun undo(stopId: String)
    fun moveStop(stopId: String, to: Int)
    fun optimize()
    fun update()
    fun startRoute()
    fun prev()
    fun next()
    fun importCsv()
    fun focusSearch()
    fun clearRoute()
    fun pickDropOff(stopId: String)
    /** Start (depot) controls. */
    fun editStart()
    fun useMyLocation()
    fun searchStart()
    fun pickStartOnMap()
}
