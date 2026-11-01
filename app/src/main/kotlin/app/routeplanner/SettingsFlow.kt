package app.routeplanner

import android.app.Activity
import app.routeplanner.core.NavApp
import app.routeplanner.core.SuggestRanker
import app.routeplanner.ui.Dialogs
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The Settings dialog and what its buttons do. File imports go back to the activity ([pickDocument] with a request code
 * its onActivityResult knows), as do the permissions screen and the offline-maps manager.
 */
class SettingsFlow(
    private val act: Activity,
    private val prefs: Prefs,
    private val ui: RouteActions.Feedback,
    private val openPermissions: () -> Unit,
    private val offlineMaps: () -> Unit,
    private val storage: () -> Unit,
    private val tips: () -> Unit,
    private val pickDocument: (Int) -> Unit,
    private val reqAddresses: Int,
    private val reqTiles: Int,
) {
    fun show() {
        val tiles = Routing.tilesFile(act)
        val model = Dialogs.SettingsModel(
            waze = prefs.navApp == NavApp.WAZE,
            autoNavigate = prefs.autoNavigate,
            showMap = prefs.showMap,
            serverUrl = prefs.valhallaUrl,
            geocoderUrl = prefs.geocoderUrl,
            googleSuggest = prefs.googleSuggest,
            googleTransport = GoogleTransport.description,
            addressInfo = AddressPack.summary(act) ?: "No address data. Import a Tennessee addresses.sqlite to look up house addresses offline.",
            addressAttribution = AddressPack.meta(act)["attribution"],
            recentCount = prefs.recents.size,
            storageInfo = "The app is using " + app.routeplanner.core.Pruning.size(Storage.usage(act).total) + " of storage (not counting offline maps).",
            diagnosticsInfo = CrashLog.summary(act),
            roadsInfo = if (tiles.exists()) "Offline road data installed (${tiles.length() / 1_000_000} MB)."
            else "No offline road data: times come from the server below, or straight-line estimates.",
        )
        Dialogs.settings(act, model, openPermissions = openPermissions,
            importAddresses = {
                pickDocument(reqAddresses)
            },
            manageRecents = { manageRecents() },
            openOfflineMaps = { offlineMaps() },
            openStorage = storage,
            showTips = tips,
            shareDiagnostics = { DiagnosticsShare.share(act) },
            clearDiagnostics = { CrashLog.clear(act); ui.snack("Crash records cleared") },
            importRoads = {
            pickDocument(reqTiles)
        }) { m ->
            prefs.navApp = if (m.waze) NavApp.WAZE else NavApp.GOOGLE
            prefs.autoNavigate = m.autoNavigate
            prefs.valhallaUrl = m.serverUrl
            prefs.geocoderUrl = m.geocoderUrl
            prefs.googleSuggest = m.googleSuggest
            SessionStore.session?.let { s -> s.navApp = prefs.navApp; s.autoNavigate = m.autoNavigate; SessionStore.commit(act) }
            if (m.showMap != prefs.showMap) { prefs.showMap = m.showMap; act.recreate() }
        }
    }

    /** Settings → recent searches: remove one, or clear all. */
    private fun manageRecents() {
        val items = prefs.recents
        if (items.isEmpty()) { ui.snack("No recent searches"); return }
        MaterialAlertDialogBuilder(act).setTitle("Recent searches")
            .setItems(items.map { it.label }.toTypedArray()) { _, i ->
                Dialogs.confirm(act, "Remove from recents?", items[i].label, "Remove") {
                    prefs.recents = prefs.recents.filter { SuggestRanker.key(it) != SuggestRanker.key(items[i]) }
                }
            }
            .setNeutralButton("Clear all") { _, _ -> prefs.recents = emptyList(); ui.snack("Recent searches cleared") }
            .setNegativeButton("Close", null).show()
    }
}
