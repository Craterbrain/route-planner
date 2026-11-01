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
    /** Stop routing: back to the planning screen, progress kept. */
    fun stopRoute()
    fun prev()
    fun next()
    fun importCsv()
    fun focusSearch()
    fun newRoute()
    /** The card's three-dot menu: New route, Save route, Open route. */
    fun routeMenu(anchor: android.view.View)
    fun pickDropOff(stopId: String)
    /** Start (depot) controls. */
    fun editStart()
    fun useMyLocation()
    fun searchStart()
    fun pickStartOnMap()
}
