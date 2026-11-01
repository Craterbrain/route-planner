package app.routeplanner

import android.app.Activity
import android.widget.EditText
import app.routeplanner.core.LatLng
import app.routeplanner.ui.Dialogs

/**
 * What happens after Scan label or "Add anyway": confirm the address, add the stop, or fall back to the search box.
 * [searchField] is the search bar, [startPoint] the route start (the last-resort position for an unplaceable address)
 * and [pickDropOff] opens the map picker for a stop so the driver can place an estimated one.
 */
class ScanFlow(
    private val act: Activity,
    private val actions: RouteActions,
    private val ui: RouteActions.Feedback,
    private val searchField: () -> EditText,
    private val startPoint: () -> LatLng?,
    private val mapAvailable: () -> Boolean,
    private val pickDropOff: (String) -> Unit,
) {
    /**
     * A tapped address from Scan label: confirm it with Google (see [AddressSearch.validate]) and add it
     * straight away; only if nothing confirms it does it go into the search bar to be picked or edited.
     */
    fun addScanned(address: String, name: String?, codes: List<String>) {
        val search = actions.addressSearch()
        val busy = Dialogs.busy(act, "Checking the address…")
        actions.background({ search.validate(address) }) { r ->
            busy.dismiss()
            if (act.isFinishing || act.isDestroyed) return@background
            val hit = r.getOrNull()
            if (hit?.point != null) actions.addStopAt(hit.label.ifEmpty { address }, hit.point!!, typed = address, approximate = hit.approximate, notes = name?.let { "For $it" }.orEmpty(), codes = codes)
            else {
                // Just fill the box: the panel looks it up and offers "Add anyway" if nothing confirms it.
                searchField().setText(address)
                searchField().let { it.setSelection(it.text.length) }
                ui.snack("Couldn't confirm that address. Pick a match, edit it, or add it anyway.")
            }
        }
    }

    /**
     * "Add anyway": the text goes on the route as typed even though nothing confirmed it. It is placed at the best
     * guess available (the top result of any source, else the town/ZIP part, else the route start), flagged as an
     * estimate, and the driver is asked to tap the map where it really is.
     */
    fun addAnyway(text: String) {
        val start = startPoint()
        val search = actions.addressSearch()
        val busy = Dialogs.busy(act, "Adding…")
        actions.background({
            fun guess(q: String) = (search.suggest(q, 3) as? AddressSearch.Outcome.Found)?.items?.firstOrNull { it.point != null }?.point
            guess(text) ?: text.substringAfter(',', "").trim().takeIf { it.length >= 3 }?.let { guess(it) } ?: start
        }) { r ->
            busy.dismiss()
            if (act.isFinishing || act.isDestroyed) return@background
            val at = r.getOrNull()
            if (at == null) { ui.snack("Set a starting point first, then add it again."); return@background }
            val id = actions.addStopAt(text, at, typed = text, approximate = true) ?: return@background
            if (mapAvailable()) {
                pickDropOff(id)
                ui.snack("Added at an estimated spot. Tap the map where it really is.")
            } else ui.snack("Added at an estimated spot. Adjust it in the stop's details.")
        }
    }
}
