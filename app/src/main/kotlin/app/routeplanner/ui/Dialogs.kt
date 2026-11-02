package app.routeplanner.ui

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import app.routeplanner.R
import app.routeplanner.ThemeMode
import app.routeplanner.core.EndMode
import app.routeplanner.core.RouteEnd
import app.routeplanner.core.FailReason
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

/** Small modal dialogs used by the route screen. */
object Dialogs {

    /** Non-cancellable progress message; call dismiss() when done. */
    fun busy(ctx: Context, title: String) =
        MaterialAlertDialogBuilder(ctx).setTitle(title).setCancelable(false).show()

    fun confirm(ctx: Context, title: String, message: String?, ok: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Why a stop failed. */
    fun failReason(ctx: Context, onPick: (FailReason) -> Unit) {
        val reasons = FAIL_LABELS.keys.toList()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Why wasn't it delivered?")
            .setItems(reasons.map { FAIL_LABELS.getValue(it) }.toTypedArray()) { _, i -> onPick(reasons[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Move to position": 1-based stop number. */
    fun moveTo(ctx: Context, current: Int, count: Int, onPick: (Int) -> Unit) {
        val picker = NumberPicker(ctx).apply {
            minValue = 1; maxValue = count.coerceAtLeast(1); value = current.coerceIn(1, count.coerceAtLeast(1))
            wrapSelectorWheel = false
        }
        MaterialAlertDialogBuilder(ctx).setTitle("Move to position").setView(picker)
            .setPositiveButton("Move") { _, _ -> onPick(picker.value) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toggleGroup(ctx: Context, options: List<String>, selected: String?): MaterialButtonToggleGroup =
        MaterialButtonToggleGroup(ctx).apply {
            isSingleSelection = true
            options.forEach { o ->
                val b = MaterialButton(ctx, null, M.attr.materialButtonOutlinedStyle).apply { id = android.view.View.generateViewId(); text = o; tag = o }
                addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (o == selected) check(b.id)
            }
        }

    private fun MaterialButtonToggleGroup.value(): String? =
        if (checkedButtonId == android.view.View.NO_ID) null else findViewById<MaterialButton>(checkedButtonId)?.tag as? String

    /**
     * Package finder location: Front/Middle/Back, Left/Right,
     * Floor/Shelf, plus free text (bin numbers etc.). Returns e.g. "Back · Left · Shelf · Bin 3".
     */
    fun zone(ctx: Context, pkgId: String, current: String?, onSet: (String?) -> Unit) {
        val parts = current?.split(" · ")?.map { it.trim() }.orEmpty()
        val box = ctx.vertical(padH = 20, padV = 8)
        val depth = toggleGroup(ctx, listOf("Front", "Middle", "Back"), parts.firstOrNull { it in setOf("Front", "Middle", "Back") })
        val side = toggleGroup(ctx, listOf("Left", "Right"), parts.firstOrNull { it in setOf("Left", "Right") })
        val level = toggleGroup(ctx, listOf("Floor", "Shelf"), parts.firstOrNull { it in setOf("Floor", "Shelf") })
        val known = setOf("Front", "Middle", "Back", "Left", "Right", "Floor", "Shelf")
        val extra = EditText(ctx).apply {
            hint = "Other (e.g. Bin 3)"
            setText(parts.filter { it !in known }.joinToString(" · "))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addFull(depth, bottomDp = 6); box.addFull(side, bottomDp = 6); box.addFull(level, bottomDp = 6); box.addFull(extra)
        MaterialAlertDialogBuilder(ctx).setTitle("Where is $pkgId?").setView(box)
            .setPositiveButton("Done") { _, _ ->
                val z = listOfNotNull(depth.value(), side.value(), level.value(), extra.text.toString().trim().ifEmpty { null })
                onSet(z.joinToString(" · ").ifEmpty { null })
            }
            .setNeutralButton("Clear") { _, _ -> onSet(null) }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Proof of delivery: optional note, photo and recipient signature. [takePhoto] launches the
     * camera for the given file; [onDone] gets the note and the photo and signature paths that exist.
     */
    fun proof(ctx: Context, photo: File, signature: File, takePhoto: (File) -> Unit,
              onDone: (String, String?, String?) -> Unit) {
        val box = ctx.vertical(padH = 20, padV = 8)
        val note = EditText(ctx).apply {
            hint = "Note (e.g. left behind back porch pot)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addFull(note)
        box.addFull(ctx.button("Take photo", M.attr.materialButtonOutlinedStyle) { takePhoto(photo) }, topDp = 8)
        val sign = ctx.button("Get signature", M.attr.materialButtonOutlinedStyle) { }
        sign.setOnClickListener {
            signature(ctx, signature) { sign.text = if (signature.exists()) "Signature captured ✓ (tap to redo)" else "Get signature" }
        }
        if (signature.exists()) sign.text = "Signature captured ✓ (tap to redo)"
        box.addFull(sign, topDp = 4)
        MaterialAlertDialogBuilder(ctx).setTitle("Proof of delivery").setView(box)
            .setPositiveButton("Delivered") { _, _ ->
                fun path(f: File) = f.takeIf { it.exists() && it.length() > 0 }?.absolutePath
                onDone(note.text.toString(), path(photo), path(signature))
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** A full-width pad the recipient signs on; saves a PNG into [file] on Save. */
    fun signature(ctx: Context, file: File, onSaved: () -> Unit) {
        val box = ctx.vertical(padH = 20, padV = 8)
        val pad = SignaturePad(ctx)
        box.addFull(pad, heightDp = 220)
        MaterialAlertDialogBuilder(ctx).setTitle("Recipient signature").setView(box)
            .setPositiveButton("Save", null)
            .setNeutralButton("Clear", null)
            .setNegativeButton("Cancel", null).show().also { d ->
                // Keep the dialog open on an empty Save / on Clear.
                d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    if (pad.savePng(file)) { onSaved(); d.dismiss() }
                    else android.widget.Toast.makeText(ctx, "Draw a signature first", android.widget.Toast.LENGTH_SHORT).show()
                }
                d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
            }
    }

    /** Long-press on the map: add a stop there, or move the current stop's drop-off pin there. */
    fun mapPoint(ctx: Context, address: String?, canAddStop: Boolean, currentStopLabel: String?,
                 onStart: () -> Unit, onEnd: () -> Unit, onAdd: (String) -> Unit, onDropOff: () -> Unit) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (canAddStop) items += "Add a stop here" to { nameStop(ctx, address ?: "", onAdd) }
        items += "Set as starting point" to onStart
        items += "Set as route end" to onEnd
        if (currentStopLabel != null) items += "Set drop-off for $currentStopLabel" to onDropOff
        MaterialAlertDialogBuilder(ctx).setTitle(address ?: "Dropped pin")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Change the route start, and choose where the route ends. */
    fun startOptions(ctx: Context, end: RouteEnd, onLocation: () -> Unit, onSearch: () -> Unit,
                     onPick: (() -> Unit)?, onSearchEnd: () -> Unit, onPickEnd: (() -> Unit)?, onEnd: (RouteEnd) -> Unit) {
        val box = ctx.vertical(padH = 20, padV = 8)
        var dlg: androidx.appcompat.app.AlertDialog? = null
        box.addFull(ctx.compact(ctx.button("Use my current location", icon = R.drawable.ic_navigation) { dlg?.dismiss(); onLocation() }))
        box.addFull(ctx.compact(ctx.tonal(ctx.button("Search an address", icon = R.drawable.ic_search) { dlg?.dismiss(); onSearch() })), topDp = 4)
        if (onPick != null) box.addFull(ctx.compact(ctx.button("Pick on the map", M.attr.materialButtonOutlinedStyle) { dlg?.dismiss(); onPick() }), topDp = 4)
        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "ROUTE ENDS" }, topDp = 16, bottomDp = 4)
        val labels = listOf("Last stop", "Start", "Address")
        val group = toggleGroup(ctx, labels, when (end.mode) {
            EndMode.ROUND_TRIP -> labels[1]; EndMode.ADDRESS -> labels[2]; else -> labels[0]
        }).apply { isSelectionRequired = true }
        box.addFull(group)
        val hint = ctx.label(12f, M.attr.colorOnSurfaceVariant)
        fun describe() {
            hint.text = when (group.value()) {
                labels[1] -> "\"Start\" plans a round trip back to the start; the drive back counts toward the finish time and distance."
                labels[2] -> end.label?.let { "Ends at: " + it.substringBefore(',') + "." } ?: "Choose where the route ends. The drive there counts toward the finish time."
                else -> "The route ends at the last stop, with no return drive."
            }
        }
        describe()
        group.addOnButtonCheckedListener { _, _, _ -> describe() }
        box.addFull(hint, topDp = 4)
        val endButtons = ctx.vertical()
        endButtons.addFull(ctx.compact(ctx.tonal(ctx.button("Search the end address", icon = R.drawable.ic_search) { dlg?.dismiss(); onSearchEnd() })), topDp = 4)
        if (onPickEnd != null) endButtons.addFull(ctx.compact(ctx.button("Pick the end on the map", M.attr.materialButtonOutlinedStyle) { dlg?.dismiss(); onPickEnd() }), topDp = 4)
        box.addFull(endButtons)
        fun showEndButtons() { endButtons.visibility = if (group.value() == labels[2]) android.view.View.VISIBLE else android.view.View.GONE }
        showEndButtons()
        group.addOnButtonCheckedListener { _, _, _ -> showEndButtons() }
        dlg = MaterialAlertDialogBuilder(ctx).setTitle("Starting point").setView(box)
            .setPositiveButton("Done") { _, _ ->
                when (group.value()) {
                    labels[1] -> if (end.mode != EndMode.ROUND_TRIP) onEnd(RouteEnd(EndMode.ROUND_TRIP))
                    labels[2] -> if (end.point != null) { if (end.mode != EndMode.ADDRESS) onEnd(end.copy(mode = EndMode.ADDRESS)) } else onSearchEnd()
                    else -> if (end.mode != EndMode.LAST_STOP) onEnd(RouteEnd(EndMode.LAST_STOP))
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun nameStop(ctx: Context, suggestion: String, onAdd: (String) -> Unit) {
        val field = EditText(ctx).apply {
            hint = "Address or name"
            setText(suggestion)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val box = ctx.vertical(padH = 20, padV = 8).apply { addFull(field) }
        MaterialAlertDialogBuilder(ctx).setTitle("Add stop").setView(box)
            .setPositiveButton("Add") { _, _ -> onAdd(field.text.toString().trim().ifEmpty { "Dropped pin" }) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Settings values edited by [settings]. */
    class SettingsModel(
        var waze: Boolean,
        var autoNavigate: Boolean,
        var themeMode: String,
        var floatingButton: Boolean,
        var showMap: Boolean,
        var serverUrl: String,
        var geocoderUrl: String,
        val roadsInfo: String,
        var googleSuggest: Boolean,
        val googleTransport: String,
        val addressInfo: String,
        val addressAttribution: String?,
        val recentCount: Int,
        val storageInfo: String,
        val diagnosticsInfo: String,
    )

    fun settings(ctx: Context, m: SettingsModel, openPermissions: () -> Unit, importAddresses: () -> Unit, manageRecents: () -> Unit,
                 importRoads: () -> Unit, downloadRoads: () -> Unit, openOfflineMaps: () -> Unit, openStorage: () -> Unit, showTips: () -> Unit,
                 shareDiagnostics: () -> Unit, clearDiagnostics: () -> Unit, onSave: (SettingsModel) -> Unit) {
        val box = ctx.vertical(padH = 24, padV = 8)
        box.addFull(ctx.button("Permissions & network…", M.attr.materialButtonOutlinedStyle) { openPermissions() }, bottomDp = 4)
        box.addFull(ctx.button("How it works", M.attr.materialButtonOutlinedStyle) { showTips() }, bottomDp = 8)
        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "THEME" })
        val labels = listOf("System", "Light", "Dark")
        val theme = toggleGroup(ctx, labels, labels[listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK).indexOf(m.themeMode).coerceAtLeast(0)]).apply { isSelectionRequired = true }
        box.addFull(theme, topDp = 4, bottomDp = 8)
        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "NAVIGATION" })
        val nav = toggleGroup(ctx, listOf("Google Maps", "Waze"), if (m.waze) "Waze" else "Google Maps").apply { isSelectionRequired = true }
        box.addFull(nav, topDp = 4)
        val auto = MaterialSwitch(ctx).apply { text = "Open navigation after each stop"; isChecked = m.autoNavigate }
        box.addFull(auto, topDp = 4)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "After you mark a stop Delivered or Failed, your navigation app opens straight away with directions to the next stop. Off: you tap Navigate yourself."
        })
        val floating = MaterialSwitch(ctx).apply { text = "Floating button"; isChecked = m.floatingButton }
        box.addFull(floating, topDp = 4)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "A small button over Google Maps or Waze while a route is running, to mark Delivered or Failed without switching apps. Needs \"Display over other apps\"."
        })

        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "MAP" }, topDp = 16)
        val map = MaterialSwitch(ctx).apply { text = "Show map"; isChecked = m.showMap }
        box.addFull(map)
        box.addFull(ctx.button("Offline maps…", M.attr.materialButtonOutlinedStyle) { openOfflineMaps() }, topDp = 4, bottomDp = 4)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "Map tiles: OpenFreeMap (© OpenMapTiles, © OpenStreetMap contributors). Needs a connection unless you save an area with Offline maps; areas you've viewed are cached. The stop list works without it."
        })

        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "ROAD TIMES" }, topDp = 16)
        box.addFull(ctx.label(14f).apply { text = m.roadsInfo })
        box.addFull(ctx.button("Download offline data…", M.attr.materialButtonOutlinedStyle) { downloadRoads() }, topDp = 4)
        box.addFull(ctx.button("Import road data (.tar)", M.attr.materialButtonOutlinedStyle) { importRoads() }, topDp = 4)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply { text = "Optional Valhalla server (used only without road data):" }, topDp = 8)
        val url = EditText(ctx).apply {
            hint = "Server address, e.g. http://192.168.1.10:8002"
            setText(m.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addFull(url)

        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "ADDRESS SEARCH" }, topDp = 16)
        val google = MaterialSwitch(ctx).apply { text = "Google suggestions (unofficial)"; isChecked = m.googleSuggest }
        box.addFull(google)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "Suggestions while you type come from Google Maps' web search box, without an account or API key. " +
                "This sends what you type, your approximate location (the route start) and your IP address to Google. " +
                "It is not an official API, it is against Google's terms, and it may stop working. " +
                "Off: only the offline address data, the phone's geocoder and OpenStreetMap (Photon) are used. " +
                "Connection: ${m.googleTransport}."
        })
        box.addFull(ctx.button("Recent searches (${m.recentCount})…", M.attr.materialButtonOutlinedStyle) { manageRecents() }, topDp = 4)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "Full addresses (spreadsheets, a typed address) are resolved with the offline address data first, then the phone's geocoder (Google-backed on phones with Play services), then Google, then Photon. " +
                "Photon server (blank = public photon.komoot.io; what you type is sent there when it is used):"
        }, topDp = 8)
        val geo = EditText(ctx).apply {
            hint = "https://photon.komoot.io"
            setText(m.geocoderUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addFull(geo)

        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "ADDRESS DATA (OFFLINE)" }, topDp = 16)
        box.addFull(ctx.label(14f).apply { text = m.addressInfo })
        box.addFull(ctx.button("Import address data (.sqlite)", M.attr.materialButtonOutlinedStyle) { importAddresses() }, topDp = 4)
        m.addressAttribution?.let { a ->
            box.addFull(ctx.label(12f, M.attr.colorOnSurfaceVariant).apply {
                text = a + "\nSource data is a US federal government work (public domain)."
            }, topDp = 4)
        }
        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "STORAGE" }, topDp = 16)
        box.addFull(ctx.label(14f).apply { text = m.storageInfo })
        box.addFull(ctx.button("Manage storage…", M.attr.materialButtonOutlinedStyle) { openStorage() }, topDp = 4)

        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "DIAGNOSTICS" }, topDp = 16)
        box.addFull(ctx.label(14f).apply { text = m.diagnosticsInfo })
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "If the app crashes, a short report is kept here: versions, the device model, where in the code it failed (never what you typed) and recent log lines. It leaves the phone only if you share it."
        })
        box.addFull(ctx.button("Share diagnostic log", M.attr.materialButtonOutlinedStyle) { shareDiagnostics() }, topDp = 4)
        box.addFull(ctx.button("Clear crash records", M.attr.borderlessButtonStyle) { clearDiagnostics() })
        // Every change applies as it is made (no Save button). Typed text is taken when the field loses focus and when the dialog closes.
        val apply = {
            m.waze = nav.value() == "Waze"
            m.autoNavigate = auto.isChecked
            m.themeMode = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)[labels.indexOf(theme.value() ?: "System").coerceAtLeast(0)]
            m.floatingButton = floating.isChecked
            m.showMap = map.isChecked
            m.serverUrl = url.text.toString()
            m.geocoderUrl = geo.text.toString()
            m.googleSuggest = google.isChecked
            onSave(m)
        }
        val groupChanged = MaterialButtonToggleGroup.OnButtonCheckedListener { _, _, checked -> if (checked) apply() }
        nav.addOnButtonCheckedListener(groupChanged)
        theme.addOnButtonCheckedListener(groupChanged)
        for (sw in listOf(auto, floating, map, google)) sw.setOnCheckedChangeListener { _, _ -> apply() }
        for (f in listOf(url, geo)) f.setOnFocusChangeListener { _, focused -> if (!focused) apply() }
        MaterialAlertDialogBuilder(ctx).setTitle("Settings").setView(ScrollView(ctx).apply { addView(box) })
            .setOnDismissListener { apply() }
            .setNegativeButton("Close", null).show()
    }
}

/** Offline map manager: saved maps with delete, plus a button that downloads the current route's area. */
object OfflineMapsDialog {
    fun show(ctx: Context, saved: List<app.routeplanner.OfflineMaps.Saved>, canDownload: Boolean,
             onDownload: () -> Unit, onDelete: (app.routeplanner.OfflineMaps.Saved) -> Unit) {
        val box = ctx.vertical(padH = 20, padV = 8)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "Saves the map around your route so it loads without a connection. Each download is limited to 3,000 tiles (about 50 MB) per OpenFreeMap's terms; a large area gets a lower zoom."
        }, bottomDp = 8)
        if (saved.isEmpty()) box.addFull(ctx.label(14f).apply { text = "No offline maps saved." })
        var dlg: androidx.appcompat.app.AlertDialog? = null
        for (s in saved) {
            val row = ctx.horizontal()
            val texts = ctx.vertical()
            texts.addFull(ctx.label(15f, bold = true, lines = 1).apply { text = s.name })
            texts.addFull(ctx.label(12f, M.attr.colorOnSurfaceVariant).apply {
                text = "%.1f MB".format(java.util.Locale.ROOT, s.bytes / 1_000_000.0) + if (s.complete) "" else " · incomplete"
            })
            row.addWeighted(texts)
            row.addView(ctx.button("Delete", M.attr.borderlessButtonStyle) { dlg?.dismiss(); onDelete(s) })
            box.addFull(row, topDp = 4)
        }
        val b = MaterialAlertDialogBuilder(ctx).setTitle("Offline maps").setView(box).setNegativeButton("Close", null)
        if (canDownload) b.setPositiveButton("Download route area") { _, _ -> onDownload() }
        dlg = b.show()
    }
}

/** Saved routes: tap one to open it, Delete to remove it. */
object RouteLibraryDialog {
    fun show(ctx: Context, entries: List<app.routeplanner.RouteLibrary.Entry>, onOpen: (app.routeplanner.RouteLibrary.Entry) -> Unit,
             onDelete: (app.routeplanner.RouteLibrary.Entry) -> Unit) {
        val box = ctx.vertical(padH = 12, padV = 8)
        if (entries.isEmpty()) box.addFull(ctx.label(14f, M.attr.colorOnSurfaceVariant).apply {
            text = "No saved routes yet. Use Save route to keep the current one."
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        })
        var dlg: androidx.appcompat.app.AlertDialog? = null
        val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        for (e in entries) {
            val row = ctx.horizontal().apply {
                setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(0), ctx.dp(6))
                isClickable = true; isFocusable = true
                val tv = android.util.TypedValue()
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) background = ctx.getDrawable(tv.resourceId)
                setOnClickListener { dlg?.dismiss(); onOpen(e) }
            }
            val texts = ctx.vertical()
            texts.addFull(ctx.label(16f, bold = true, lines = 1).apply { text = e.name })
            texts.addFull(ctx.label(12f, M.attr.colorOnSurfaceVariant).apply {
                text = "${e.stops} stop" + (if (e.stops == 1) "" else "s") + (if (e.delivered > 0) " · ${e.delivered} done" else "") +
                    " · saved " + fmt.format(java.util.Date(e.savedAt))
            })
            row.addWeighted(texts)
            row.addView(ctx.button("Delete", M.attr.borderlessButtonStyle) { dlg?.dismiss(); onDelete(e) })
            box.addFull(row)
        }
        dlg = MaterialAlertDialogBuilder(ctx).setTitle("Open route").setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setNegativeButton("Close", null).show()
    }
}
