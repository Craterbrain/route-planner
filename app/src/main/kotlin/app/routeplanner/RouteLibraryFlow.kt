package app.routeplanner

import android.app.Activity
import android.text.InputType
import app.routeplanner.core.Status
import app.routeplanner.ui.Dialogs
import app.routeplanner.ui.RouteLibraryDialog
import app.routeplanner.ui.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** New / Save / Open route: the dialogs behind the route card's ⋮ menu. The saved routes themselves are in [RouteLibrary]. */
class RouteLibraryFlow(
    private val act: Activity,
    private val actions: RouteActions,
    private val prefs: Prefs,
    private val ui: RouteActions.Feedback,
) {
    /** Clears the route (and the floating button). */
    fun discard() {
        SessionStore.set(act, null)
        OverlayService.stop(act)
    }

    /** New route: asks what to do with the one that is open. */
    fun newRoute() {
        val s = SessionStore.session
        if (s == null || s.stops.isEmpty()) { ui.snack("The route is already empty"); return }
        val done = s.stops.count { it.status != Status.PENDING }
        MaterialAlertDialogBuilder(act).setTitle("Start a new route?")
            .setMessage("The current route has ${s.stops.size} stop" + (if (s.stops.size == 1) "" else "s") + (if (done > 0) " ($done done)" else "") +
                ". Save it first if you want to come back to it.")
            .setPositiveButton("Save, then new") { _, _ -> save { discard() } }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Discard it") { _, _ -> discard() }
            .show()
    }

    /** Save route: asks for a name; saving again under the same name updates the saved copy. */
    fun save(then: (() -> Unit)? = null) {
        val s = SessionStore.session ?: return
        val field = android.widget.EditText(act).apply {
            hint = "Route name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(s.name ?: java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date()))
            setSelection(text.length)
        }
        val box = android.widget.LinearLayout(act).apply { setPadding(act.dp(24), act.dp(8), act.dp(24), 0); addView(field, android.widget.LinearLayout.LayoutParams(-1, -2)) }
        fun doSave(asNew: Boolean) {
            val name = field.text.toString().trim().ifEmpty { "Route" }
            val prepared = RouteLibrary.prepare(act, s, name, asNew)
            actions.background({ prepared.write() }) { r ->
                r.onSuccess { SessionStore.commit(act); ui.snack("Saved “$name”"); then?.invoke() }
                    .onFailure { ui.snack("Couldn't save the route: ${it.message}") }
            }
        }
        val b = MaterialAlertDialogBuilder(act).setTitle("Save route").setView(box)
            .setPositiveButton(if (s.savedId != null) "Update" else "Save") { _, _ -> doSave(asNew = false) }
            .setNegativeButton("Cancel", null)
        if (s.savedId != null) b.setNeutralButton("Save as new") { _, _ -> doSave(asNew = true) }
        b.show()
    }

    /** Open route: lists the saved routes; opening one replaces the current route. */
    fun open() {
        actions.background({ RouteLibrary.list(act) }) { r ->
            if (act.isFinishing || act.isDestroyed) return@background
            RouteLibraryDialog.show(act, r.getOrDefault(emptyList()),
                onOpen = { e -> confirmOpen(e) },
                onDelete = { e -> Dialogs.confirm(act, "Delete “${e.name}”?", "The saved copy is removed from this phone.", "Delete") {
                    actions.background({ RouteLibrary.delete(act, e.id) }) { ui.snack("Deleted “${e.name}”"); open() }
                } })
        }
    }

    private fun confirmOpen(e: RouteLibrary.Entry) {
        val current = SessionStore.session?.takeIf { it.stops.isNotEmpty() }
        val go = {
            actions.background({ RouteLibrary.load(act, e.id) }) { r ->
                val loaded = r.getOrNull()
                if (loaded == null) { ui.snack("Couldn't open “${e.name}”"); return@background }
                loaded.navApp = prefs.navApp; loaded.autoNavigate = prefs.autoNavigate
                if (loaded.start == null) loaded.start = prefs.start
                SessionStore.set(act, loaded)
                actions.replan(optimize = false, showBusy = false) // times are relative to now
                ui.snack("Opened “${e.name}”")
            }
        }
        if (current == null) go()
        else Dialogs.confirm(act, "Open “${e.name}”?", "This replaces the route that is open now. Save that one first if you need it.", "Open") { go() }
    }
}
