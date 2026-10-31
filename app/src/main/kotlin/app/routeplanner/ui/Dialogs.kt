package app.routeplanner.ui

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import app.routeplanner.core.FailReason
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

/** Small modal dialogs used by the route screen. */
object Dialogs {

    fun confirm(ctx: Context, title: String, message: String?, ok: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Circuit's failure reasons. */
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
     * Package finder location, as in Circuit: Front/Middle/Back, Left/Right,
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
     * Proof of delivery: optional note and photo. [takePhoto] launches the
     * camera for the given file; [onDone] gets the note and the photo path if one was taken.
     */
    fun proof(ctx: Context, photo: File, takePhoto: (File) -> Unit, onDone: (String, String?) -> Unit) {
        val box = ctx.vertical(padH = 20, padV = 8)
        val note = EditText(ctx).apply {
            hint = "Note (e.g. left behind back porch pot)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addFull(note)
        box.addFull(ctx.button("Take photo", M.attr.materialButtonOutlinedStyle) { takePhoto(photo) }, topDp = 8)
        MaterialAlertDialogBuilder(ctx).setTitle("Proof of delivery").setView(box)
            .setPositiveButton("Delivered") { _, _ ->
                onDone(note.text.toString(), photo.takeIf { it.exists() && it.length() > 0 }?.absolutePath)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Long-press on the map: add a stop there, or move the current stop's drop-off pin there. */
    fun mapPoint(ctx: Context, address: String?, currentStopLabel: String?, onAdd: (String) -> Unit, onDropOff: () -> Unit) {
        val items = buildList {
            add("Add a stop here")
            if (currentStopLabel != null) add("Set drop-off for $currentStopLabel")
        }
        MaterialAlertDialogBuilder(ctx).setTitle(address ?: "Dropped pin")
            .setItems(items.toTypedArray()) { _, i ->
                if (i == 0) nameStop(ctx, address ?: "", onAdd) else onDropOff()
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
        var showMap: Boolean,
        var serverUrl: String,
        var geocoderUrl: String,
        val roadsInfo: String,
    )

    fun settings(ctx: Context, m: SettingsModel, openPermissions: () -> Unit, importRoads: () -> Unit, onSave: (SettingsModel) -> Unit) {
        val box = ctx.vertical(padH = 24, padV = 8)
        box.addFull(ctx.button("Permissions & network…", M.attr.materialButtonOutlinedStyle) { openPermissions() }, bottomDp = 8)
        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "NAVIGATION" })
        val nav = toggleGroup(ctx, listOf("Google Maps", "Waze"), if (m.waze) "Waze" else "Google Maps").apply { isSelectionRequired = true }
        box.addFull(nav, topDp = 4)
        val auto = MaterialSwitch(ctx).apply { text = "Open navigation after each stop"; isChecked = m.autoNavigate }
        box.addFull(auto, topDp = 4)

        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "MAP" }, topDp = 16)
        val map = MaterialSwitch(ctx).apply { text = "Show map"; isChecked = m.showMap }
        box.addFull(map)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "Map tiles: OpenFreeMap (© OpenMapTiles, © OpenStreetMap contributors). Needs a connection; areas you've viewed are cached. The stop list works without it."
        })

        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "ROAD TIMES" }, topDp = 16)
        box.addFull(ctx.label(14f).apply { text = m.roadsInfo })
        box.addFull(ctx.button("Import road data (.tar)", M.attr.materialButtonOutlinedStyle) { importRoads() }, topDp = 4)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply { text = "Optional Valhalla server (used only without road data):" }, topDp = 8)
        val url = EditText(ctx).apply {
            hint = "http://192.168.1.10:8002"
            setText(m.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addFull(url)

        box.addFull(ctx.label(12f, M.attr.colorPrimary, bold = true).apply { text = "ADDRESS SEARCH" }, topDp = 16)
        box.addFull(ctx.label(13f, M.attr.colorOnSurfaceVariant).apply {
            text = "Uses the phone's geocoder if it has one, otherwise Photon (OpenStreetMap). What you type is sent to that server. Leave blank for the public photon.komoot.io, or enter your own Photon URL."
        })
        val geo = EditText(ctx).apply {
            hint = "https://photon.komoot.io"
            setText(m.geocoderUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addFull(geo)
        MaterialAlertDialogBuilder(ctx).setTitle("Settings").setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                m.waze = nav.value() == "Waze"
                m.autoNavigate = auto.isChecked
                m.showMap = map.isChecked
                m.serverUrl = url.text.toString()
                m.geocoderUrl = geo.text.toString()
                onSave(m)
            }
            .setNegativeButton("Close", null).show()
    }
}
