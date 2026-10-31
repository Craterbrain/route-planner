package app.routeplanner.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.routeplanner.R
import app.routeplanner.SessionStore
import app.routeplanner.color
import app.routeplanner.core.*
import com.google.android.material.R as M
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Circuit's stop details sheet: address and status, the primary actions
 * (Navigate / Failed / Delivered, or Undo), then the stop's settings: order
 * (First / Auto / Last), arrival time, time at stop, notes, package finder,
 * drop-off pin, and move / make next / remove.
 */
class StopDetailSheet(private val act: Activity, private val host: RouteHost, private val stopId: String) {
    private val dialog = BottomSheetDialog(act)
    private val body = act.vertical(padH = 20, padV = 4)
    private var notes: EditText? = null

    /** Called when the sheet closes (the map uses it to drop the selection). */
    var onClosed: (() -> Unit)? = null

    fun show() {
        dialog.setContentView(ScrollView(act).apply { addView(body) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { saveNotes(); onClosed?.invoke() }
        render()
        dialog.show()
    }

    private fun session() = SessionStore.session
    private fun commit() = SessionStore.commit(act)

    private fun saveNotes() {
        val f = notes ?: return
        val s = session() ?: return
        val text = f.text.toString().trim()
        if (s.stop(stopId)?.notes != text) { s.edit(stopId) { it.copy(notes = text) }; commit() }
    }

    /** Closes the sheet and runs [then] (used before actions that change screens or the route). */
    private fun closeThen(then: () -> Unit) { dialog.dismiss(); then() }

    private fun section(title: String) {
        body.addFull(act.label(12f, M.attr.colorPrimary, bold = true).apply { text = title.uppercase() }, topDp = 18, bottomDp = 4)
    }

    private fun render() {
        saveNotes()
        body.removeAllViews()
        notes = null
        val s = session() ?: run { dialog.dismiss(); return }
        val st = s.stop(stopId) ?: run { dialog.dismiss(); return }
        val sum = s.summary()

        // Header: badge, address, status / ETA.
        val head = act.horizontal(Gravity.TOP)
        val colors = act.badgeColors(st, s.started && s.current?.id == st.id)
        head.addView(act.label(16f, bold = true).apply {
            gravity = Gravity.CENTER
            text = badgeText(st)
            setTextColor(colors.text)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors.fill) }
        }, LinearLayout.LayoutParams(act.dp(40), act.dp(40)).apply { marginEnd = act.dp(14); topMargin = act.dp(4) })
        val (l1, l2) = splitAddress(st.address)
        val titles = act.vertical()
        titles.addFull(act.label(22f, bold = true).apply { text = l1 })
        if (l2.isNotEmpty()) titles.addFull(act.label(14f, M.attr.colorOnSurfaceVariant).apply { text = l2 })
        titles.addFull(act.label(14f, if (st.status == Status.FAILED) M.attr.colorError else M.attr.colorOnSurface).apply {
            text = when (st.status) {
                Status.PENDING -> "Stop ${st.optimizedIndex} · ETA ${act.clock(s, sum.projectedEta(st))}" +
                    (st.legMeters.takeIf { it > 0 }?.let { " · " + formatDistance(it, imperialUnits()) } ?: "")
                Status.COMPLETED -> "Delivered" + (st.completedAt?.let { " at " + time(it) } ?: "")
                Status.FAILED -> "Failed: " + (st.failReason?.let { FAIL_LABELS[it] } ?: "no reason") + (st.completedAt?.let { " at " + time(it) } ?: "")
                Status.SKIPPED -> "Skipped"
            }
        }, topDp = 4)
        head.addWeighted(titles)
        body.addFull(head, topDp = 4)

        // Primary actions.
        val actions = act.horizontal()
        actions.addWeighted(act.compact(act.tonal(act.button("Navigate", icon = R.drawable.ic_navigation) { closeThen { host.navigate(st.id) } })), marginDp = 3)
        if (st.status == Status.PENDING) {
            actions.addWeighted(act.compact(act.button("Failed", M.attr.materialButtonOutlinedStyle) { closeThen { host.fail(st.id) } }), marginDp = 3)
            actions.addWeighted(act.compact(act.button("Delivered", icon = R.drawable.ic_check) { closeThen { host.deliver(st.id) } }), marginDp = 3)
        } else {
            actions.addWeighted(act.button("Undo", M.attr.materialButtonOutlinedStyle) { host.undo(st.id); render() }, marginDp = 3)
        }
        body.addFull(actions, topDp = 12)
        if (st.status == Status.PENDING) body.addFull(act.button("Delivered with photo or note…", M.attr.borderlessButtonStyle) {
            closeThen { host.deliverWithProof(st.id) }
        })

        // Proof of delivery (finished stops).
        if (st.proofNote != null || st.proofPhoto != null || st.proofSignature != null) {
            section("Proof of delivery")
            st.proofNote?.let { body.addFull(act.label(15f).apply { text = it }) }
            st.proofPhoto?.let { path -> thumbnail(path)?.let { body.addFull(it, topDp = 6) } }
            st.proofSignature?.let { path -> thumbnail(path, "Recipient signature")?.let { body.addFull(it, topDp = 6) } }
        }

        if (st.status == Status.PENDING) {
            section("Order")
            body.addFull(placementToggle(st))
        }

        section("Arrival time")
        body.addFull(row(st.window?.let { act.windowLabel(s, it) } ?: "Anytime", "Change") { pickWindow(s, st) })

        section("Time at stop")
        val minutes = st.serviceSec / 60
        val svc = act.horizontal()
        svc.addWeighted(act.label(15f).apply { text = "$minutes min" })
        svc.addView(act.button("−", M.attr.materialButtonOutlinedStyle) { setService(st, (minutes - 1).coerceAtLeast(0)) })
        svc.addView(act.button("+", M.attr.materialButtonOutlinedStyle) { setService(st, minutes + 1) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = act.dp(8) })
        body.addFull(svc)

        section("Notes")
        notes = EditText(act).apply {
            hint = "Gate code, where to leave it…"
            setText(st.notes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        body.addFull(notes!!)

        section("Package finder")
        val pkgs = s.finder(st)
        if (pkgs.isEmpty()) body.addFull(act.label(14f, M.attr.colorOnSurfaceVariant).apply { text = "No packages listed for this stop." })
        for (p in pkgs) body.addFull(row("${p.id}\n${p.truckZone ?: "Location not set"}", "Set location") {
            Dialogs.zone(act, p.id, p.truckZone) { z -> session()?.setZone(p.id, z); commit(); render() }
        })

        section("Drop-off point")
        val moved = st.access != st.target
        body.addFull(act.label(14f, M.attr.colorOnSurfaceVariant).apply {
            text = when {
                moved -> "Navigation goes to your adjusted pin (remembered for this address)."
                st.approximate -> "Estimated position (interpolated or street-level). Adjust the pin so navigation goes to the right door."
                else -> "Navigation goes to the address pin."
            }
        })
        if (host.mapAvailable) body.addFull(act.button("Adjust pin on map", M.attr.materialButtonOutlinedStyle) {
            closeThen { host.pickDropOff(st.id) }
        }, topDp = 4)

        section("Stop")
        if (st.status == Status.PENDING && s.started && s.current?.id != st.id) {
            body.addFull(act.button("Make next", M.attr.borderlessButtonStyle) {
                session()?.makeNext(st.id); commit(); host.update(); render()
            }.leftAligned())
        }
        if (st.status == Status.PENDING) body.addFull(act.button("Move to position…", M.attr.borderlessButtonStyle) {
            Dialogs.moveTo(act, st.optimizedIndex, s.stops.size) { n -> host.moveStop(st.id, n - 1); render() }
        }.leftAligned())
        if (s.started && s.current?.id != st.id) body.addFull(act.button("Work on this stop now", M.attr.borderlessButtonStyle) {
            closeThen { session()?.jumpTo(st.id); commit() }
        }.leftAligned())
        body.addFull(act.button("Remove stop", M.attr.borderlessButtonStyle) {
            Dialogs.confirm(act, "Remove stop?", st.address, "Remove") {
                notes = null // nothing to save on a removed stop
                session()?.removeStop(st.id); commit(); dialog.dismiss(); host.update()
            }
        }.apply { setTextColor(act.color(M.attr.colorError)) }.leftAligned(), bottomDp = 16)
    }

    private fun MaterialButton.leftAligned() = apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }

    private fun time(ms: Long) = android.text.format.DateFormat.getTimeFormat(act).format(java.util.Date(ms))

    /** A text line with a trailing action button. */
    private fun row(text: String, action: String, onClick: () -> Unit) = act.horizontal().apply {
        addWeighted(act.label(15f).apply { this.text = text })
        addView(act.button(action, M.attr.borderlessButtonStyle) { onClick() })
    }

    private fun placementToggle(st: Stop): MaterialButtonToggleGroup = MaterialButtonToggleGroup(act).apply {
        isSingleSelection = true
        isSelectionRequired = true
        val ids = Placement.values().associateWith { android.view.View.generateViewId() }
        for (p in Placement.values()) {
            addView(MaterialButton(act, null, M.attr.materialButtonOutlinedStyle).apply { id = ids.getValue(p); text = placementLabel(p) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        check(ids.getValue(st.placement))
        addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val p = ids.entries.first { it.value == id }.key
            val s = session() ?: return@addOnButtonCheckedListener
            if (s.stop(stopId)?.placement != p) {
                s.edit(stopId) { it.copy(placement = p) }
                commit()
                Toast.makeText(act, "Optimize to apply the new order", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setService(st: Stop, minutes: Int) {
        session()?.edit(st.id) { it.copy(serviceSec = minutes * 60) }
        commit(); render()
    }

    /** Route-clock seconds for wall-clock [h]:[m] on the route's day. */
    private fun routeSec(s: RouteSession, h: Int, m: Int): Long {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
        val ms = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
        return (ms - s.startMs) / 1000
    }

    private fun wallClock(s: RouteSession, sec: Long): LocalTime =
        Instant.ofEpochMilli(s.startMs + sec * 1000).atZone(ZoneId.systemDefault()).toLocalTime()

    /** Circuit-style arrival time: earliest ("after"), latest ("before"), or anytime. */
    private fun pickWindow(s: RouteSession, st: Stop) {
        val w = st.window
        val after = w?.first?.takeIf { it > WINDOW_OPEN_START }
        val before = w?.last?.takeIf { it < WINDOW_OPEN_END }
        fun setWindow(a: Long?, b: Long?) {
            if (a != null && b != null && a > b) { Toast.makeText(act, "Earliest must be before latest", Toast.LENGTH_SHORT).show(); return }
            val nw = if (a == null && b == null) null else (a ?: WINDOW_OPEN_START)..(b ?: WINDOW_OPEN_END)
            session()?.edit(st.id) { it.copy(window = nw) }
            commit(); render()
        }
        fun pick(initial: Long?, onSet: (Long) -> Unit) {
            val t = initial?.let { wallClock(s, it) } ?: LocalTime.now().withSecond(0)
            TimePickerDialog(act, { _, h, m -> onSet(routeSec(s, h, m)) }, t.hour, t.minute,
                android.text.format.DateFormat.is24HourFormat(act)).show()
        }
        val items = arrayOf(
            "Arrive after… " + (after?.let { act.clock(s, it.toInt()) } ?: ""),
            "Arrive before… " + (before?.let { act.clock(s, it.toInt()) } ?: ""),
            "Anytime",
        )
        MaterialAlertDialogBuilder(act).setTitle("Arrival time").setItems(items) { _, i ->
            when (i) {
                0 -> pick(after) { setWindow(it, before) }
                1 -> pick(before) { setWindow(after, it) }
                else -> setWindow(null, null)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    /** Downsampled proof photo (never loads the full camera image). */
    private fun thumbnail(path: String, description: String = "Proof photo"): ImageView? {
        val f = File(path)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 600) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return ImageView(act).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            maxHeight = act.dp(240)
            contentDescription = description
        }
    }
}
