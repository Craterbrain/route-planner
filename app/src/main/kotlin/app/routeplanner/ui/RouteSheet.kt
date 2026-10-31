package app.routeplanner.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.routeplanner.R
import app.routeplanner.color
import app.routeplanner.core.*
import com.google.android.material.R as M
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.card.MaterialCardView

/**
 * The stop-list bottom sheet under the map, Circuit-style:
 * - empty: how to add stops;
 * - planning: route summary + Optimize / Start route;
 * - driving: a next-stop card with Navigate, Failed, Delivered and prev/next;
 * - finished: a completion summary.
 * Below the header is the full stop list.
 */
class RouteSheet(private val ctx: Context, private val host: RouteHost) {
    val view: LinearLayout = ctx.vertical()
    /** Everything above the list; its height is the sheet's collapsed (peek) height. */
    val header: LinearLayout = ctx.vertical(padH = 16)
    val handle = BottomSheetDragHandleView(ctx)
    private val adapter = StopAdapter(ctx, host)
    val list = RecyclerView(ctx)
    /** The route start, shown as the first row above the stops. */
    private val startRow = ctx.vertical()

    init {
        val r = ctx.dpf(28f)
        view.background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(ctx.color(M.attr.colorSurfaceContainerLow))
        }
        ViewCompat.setElevation(view, ctx.dpf(6f))
        view.isClickable = true // don't let taps fall through to the map
        view.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.clipToPadding = false
        list.setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(8))
        adapter.touchHelper.attachToRecyclerView(list)
        view.addView(startRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Bottom system-bar inset, so the last row isn't under the navigation bar. */
    fun setBottomInset(px: Int) = list.setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(8) + px)

    fun render(s: RouteSession?, start: RouteStart?, end: EndMode) {
        header.removeAllViews()
        renderStartRow(start, end)
        if (start == null) {
            startCard()
            // A route saved before starts existed still shows, below the card.
            if (s == null || s.stops.isEmpty()) { if (!adapter.dragging) adapter.submit(emptyList()); return }
        } else if (s == null || s.stops.isEmpty()) {
            emptyState()
            if (!adapter.dragging) adapter.submit(emptyList())
            return
        }
        val sum = s.summary()
        when {
            !s.started -> planningHeader(s, sum)
            s.done -> finishedHeader(s, sum)
            else -> drivingHeader(s, sum)
        }
        if (!adapter.dragging) adapter.submit(rows(s, sum))
    }

    // ---- Start -----------------------------------------------------------------

    /** Gate: nothing can be planned until the route has a start. */
    private fun startCard() {
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.color(M.attr.colorPrimaryContainer))
            radius = ctx.dpf(24f); cardElevation = 0f; strokeWidth = 0
        }
        val body = ctx.vertical(padH = 16, padV = 14)
        val on = M.attr.colorOnPrimaryContainer
        body.addFull(ctx.label(22f, on, bold = true).apply { text = "Set your starting point" })
        body.addFull(ctx.label(14f, on).apply {
            text = "Routes are planned from here, and address search looks around it first."
        }, topDp = 4, bottomDp = 10)
        body.addFull(ctx.compact(ctx.button("Use my current location", icon = R.drawable.ic_navigation) { host.useMyLocation() }))
        body.addFull(ctx.compact(ctx.tonal(ctx.button("Search an address", icon = R.drawable.ic_search) { host.searchStart() })), topDp = 4)
        body.addFull(ctx.compact(ctx.button("Pick on the map", M.attr.materialButtonOutlinedStyle) { host.pickStartOnMap() }), topDp = 4)
        card.addView(body)
        header.addFull(card, bottomDp = 8)
    }

    private fun renderStartRow(start: RouteStart?, end: EndMode) {
        startRow.removeAllViews()
        if (start == null) return
        val row = ctx.horizontal().apply {
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            isClickable = true
            isFocusable = true
            val tv = android.util.TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) foreground = ctx.getDrawable(tv.resourceId)
            setOnClickListener { host.editStart() }
            contentDescription = "Route start: ${start.label}. Tap to change."
        }
        row.addView(ctx.label(14f, M.attr.colorOnSecondary, bold = true).apply {
            text = "S"
            gravity = Gravity.CENTER
            background = ctx.roundRect(M.attr.colorSecondary, 8)
        }, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { marginEnd = ctx.dp(12) })
        val texts = ctx.vertical()
        texts.addFull(ctx.label(15f, bold = true, lines = 1).apply {
            text = if (start.live) "Start · My location" else "Start · ${splitAddress(start.label).first}"
        })
        texts.addFull(ctx.label(12f, M.attr.colorOnSurfaceVariant, lines = 2).apply {
            val detail = if (start.live) listOfNotNull(
                start.accuracyM?.let { "±${it.toInt()} m" },
                start.fixAtMs?.let { "updated " + ago(it) },
            ).joinToString(" · ").ifEmpty { "no fix yet" } else splitAddress(start.label).second
            val ends = when (end) { EndMode.ROUND_TRIP -> "returns here"; EndMode.ADDRESS -> "ends at chosen address"; EndMode.LAST_STOP -> "ends at last stop" }
            text = listOf(detail, ends).filter { it.isNotEmpty() }.joinToString(" · ")
        })
        row.addWeighted(texts)
        if (start.live) row.addView(ctx.button("Refresh", M.attr.borderlessButtonStyle) { host.useMyLocation() })
        else row.addView(ctx.button("Change", M.attr.borderlessButtonStyle) { host.editStart() })
        startRow.addFull(row)
    }

    private fun ago(ms: Long): String {
        val min = ((System.currentTimeMillis() - ms) / 60_000).toInt()
        return when { min < 1 -> "just now"; min < 60 -> "$min min ago"; else -> "${min / 60} h ago" }
    }

    // ---- Headers -------------------------------------------------------------

    private fun emptyState() {
        header.addFull(ctx.label(22f, bold = true).apply { text = "Plan your route" }, topDp = 4)
        header.addFull(ctx.label(14f, M.attr.colorOnSurfaceVariant).apply {
            text = "Add stops with the search bar (type or speak); results near your start come first. You can also long-press the map to drop one, or import a spreadsheet of addresses."
        }, topDp = 4, bottomDp = 12)
        val row = ctx.horizontal()
        row.addWeighted(ctx.compact(ctx.button("Import spreadsheet", M.attr.materialButtonOutlinedStyle) { host.importCsv() }), marginDp = 4)
        row.addWeighted(ctx.compact(ctx.button("Add stop", icon = R.drawable.ic_add) { host.focusSearch() }), marginDp = 4)
        header.addFull(row, bottomDp = 12)
    }

    private fun summaryLine(s: RouteSession, sum: RouteSummary, remainingOnly: Boolean): String {
        val imperial = imperialUnits()
        val dist = if (remainingOnly) sum.remainingMeters else sum.totalMeters
        return listOfNotNull(
            formatDuration(sum.remainingSec),
            dist.takeIf { it > 0 }?.let { formatDistance(it, imperial) },
            "done by ${ctx.clock(s, sum.finishSec)}",
        ).joinToString(" · ")
    }

    private fun planningHeader(s: RouteSession, sum: RouteSummary) {
        header.addFull(ctx.label(22f, bold = true).apply {
            text = if (sum.total == 1) "1 stop" else "${sum.total} stops"
        }, topDp = 0)
        header.addFull(ctx.label(14f, M.attr.colorOnSurfaceVariant).apply { text = summaryLine(s, sum, remainingOnly = true) })
        if (sum.lateStops > 0) header.addFull(ctx.label(13f, M.attr.colorError).apply {
            text = "${sum.lateStops} ${if (sum.lateStops == 1) "stop" else "stops"} can't be reached on time"
        }, topDp = 2)
        if (s.dirty) changedBanner()
        val row = ctx.horizontal()
        row.addWeighted(ctx.compact(ctx.tonal(ctx.button("Optimize") { host.optimize() })), marginDp = 4)
        row.addWeighted(ctx.compact(ctx.button("Start route", icon = R.drawable.ic_navigation) { host.startRoute() }), marginDp = 4)
        header.addFull(row, topDp = 10, bottomDp = 8)
    }

    /** Circuit-style "preview changes": keep the order with fresh times, or re-optimize. */
    private fun changedBanner() {
        val box = ctx.horizontal().apply {
            background = ctx.roundRect(M.attr.colorTertiaryContainer, 12)
            setPadding(ctx.dp(12), ctx.dp(2), ctx.dp(4), ctx.dp(2))
        }
        box.addWeighted(ctx.label(13f, M.attr.colorOnTertiaryContainer).apply { text = "Route changed" })
        box.addView(ctx.button("Update", M.attr.borderlessButtonStyle) { host.update() })
        box.addView(ctx.button("Optimize", M.attr.borderlessButtonStyle) { host.optimize() })
        header.addFull(box, topDp = 8, bottomDp = 4)
    }

    private fun drivingHeader(s: RouteSession, sum: RouteSummary) {
        val c = s.current ?: return
        if (s.dirty) changedBanner()
        val card = MaterialCardView(ctx).apply {
            setCardBackgroundColor(ctx.color(M.attr.colorPrimaryContainer))
            radius = ctx.dpf(24f)
            cardElevation = 0f
            strokeWidth = 0
        }
        val body = ctx.vertical(padH = 12, padV = 8)
        card.addView(body)
        val onCard = M.attr.colorOnPrimaryContainer

        // [<]  Stop 3 of 12 · ETA 10:42  [>]
        val top = ctx.horizontal()
        top.addView(ctx.iconButton(R.drawable.ic_chevron_left, "Previous stop") { host.prev() })
        val pendingAfter = s.stops.count { it.status == Status.PENDING }
        top.addWeighted(ctx.label(14f, onCard).apply {
            gravity = Gravity.CENTER
            text = "Stop ${c.optimizedIndex} of ${s.stops.size}" +
                (if (c.status == Status.PENDING) " · ETA ${ctx.clock(s, sum.projectedEta(c))}" else " · ${c.status.name.lowercase()}") +
                "\n$pendingAfter left · ${summaryLine(s, sum, remainingOnly = true)}"
        })
        top.addView(ctx.iconButton(R.drawable.ic_chevron_right, "Next stop") { host.next() })
        body.addFull(top)

        val (l1, l2) = splitAddress(c.address)
        val addr = ctx.vertical().apply {
            isClickable = true
            setOnClickListener { host.openStop(c.id) }
        }
        addr.addFull(ctx.label(22f, onCard, bold = true, lines = 2).apply { text = l1 })
        if (l2.isNotEmpty()) addr.addFull(ctx.label(14f, onCard, lines = 1).apply { text = l2 })
        val tags = conditions(s, c, sum)
        if (tags.isNotEmpty()) addr.addFull(ctx.label(13f, if (c.lateSec > 0) M.attr.colorError else onCard).apply { text = tags }, topDp = 4)
        if (c.notes.isNotBlank()) addr.addFull(ctx.label(13f, onCard, lines = 3).apply { text = "Note: ${c.notes}" }, topDp = 2)
        body.addFull(addr, topDp = 2)

        // Package finder: what to grab and where it is in the vehicle.
        val pkgs = s.finder(c)
        if (pkgs.isNotEmpty()) {
            val pf = ctx.vertical(padH = 12, padV = 8).apply { background = ctx.roundRect(M.attr.colorSurface, 14) }
            pf.addFull(ctx.label(11f, M.attr.colorOnSurfaceVariant, bold = true).apply { text = "PACKAGE FINDER" })
            for (p in pkgs) pf.addFull(ctx.label(15f).apply {
                text = "${p.id}  ·  ${p.truckZone ?: "no location set"}"
            }, topDp = 2)
            body.addFull(pf, topDp = 8)
        }

        body.addFull(ctx.tonal(ctx.button("Navigate", icon = R.drawable.ic_navigation) { host.navigate(c.id) }), topDp = 8)
        val actions = ctx.horizontal()
        if (c.status == Status.PENDING) {
            actions.addWeighted(ctx.compact(ctx.button("Failed", M.attr.materialButtonOutlinedStyle) { host.fail(c.id) }), 1f, height = ctx.dp(56), marginDp = 2)
            actions.addWeighted(ctx.compact(ctx.button("Proof", M.attr.materialButtonOutlinedStyle) { host.deliverWithProof(c.id) }), 0.9f, height = ctx.dp(56), marginDp = 2)
            actions.addWeighted(ctx.compact(ctx.button("Delivered", icon = R.drawable.ic_check) { host.deliver(c.id) }).apply {
                setOnLongClickListener { host.deliverWithProof(c.id); true }
            }, 1.5f, height = ctx.dp(56), marginDp = 2)
        } else {
            actions.addWeighted(ctx.button("Undo", M.attr.materialButtonOutlinedStyle) { host.undo(c.id) }, marginDp = 2)
        }
        body.addFull(actions, topDp = 2)
        header.addFull(card, topDp = 0, bottomDp = 8)
    }

    private fun finishedHeader(s: RouteSession, sum: RouteSummary) {
        header.addFull(ctx.label(22f, bold = true).apply { text = "Route complete" })
        header.addFull(ctx.label(14f, M.attr.colorOnSurfaceVariant).apply {
            text = listOfNotNull(
                "${sum.delivered} delivered",
                sum.failed.takeIf { it > 0 }?.let { "$it failed" },
                sum.totalMeters.takeIf { it > 0 }?.let { formatDistance(it, imperialUnits()) },
            ).joinToString(" · ")
        })
        val row = ctx.horizontal()
        row.addWeighted(ctx.compact(ctx.button("New route", M.attr.materialButtonOutlinedStyle) { host.clearRoute() }), marginDp = 4)
        row.addWeighted(ctx.compact(ctx.button("Import spreadsheet") { host.importCsv() }), marginDp = 4)
        header.addFull(row, topDp = 10, bottomDp = 8)
    }

    // ---- Rows ----------------------------------------------------------------

    /** "First · 09:00–11:00 · late 12 min" for a stop. */
    private fun conditions(s: RouteSession, st: Stop, sum: RouteSummary): String = listOfNotNull(
        st.placement.takeIf { it != Placement.AUTO }?.let { placementLabel(it) },
        st.window?.let { ctx.windowLabel(s, it) },
        st.window?.takeIf { st.status == Status.PENDING && sum.projectedEta(st) > it.last }?.let { "late" },
    ).joinToString(" · ")

    private fun rows(s: RouteSession, sum: RouteSummary): List<StopAdapter.Row> {
        val cur = s.current?.takeIf { s.started }
        return s.stops.map { st ->
            val (l1, l2) = splitAddress(st.address)
            val pkgs = s.finder(st)
            val cond = conditions(s, st, sum)
            val meta = listOfNotNull(
                if (st.approximate && st.access == st.target) "Estimated position, adjust pin" else null,
                cond.ifEmpty { null },
                pkgs.takeIf { it.isNotEmpty() }?.let { p ->
                    val zones = p.mapNotNull { it.truckZone }.distinct()
                    "${p.size} pkg" + (if (p.size > 1) "s" else "") + if (zones.isEmpty()) "" else ": " + zones.joinToString(", ")
                },
                st.notes.takeIf { it.isNotBlank() }?.let { "Note: $it" },
                st.failReason?.let { FAIL_LABELS[it] },
            ).joinToString(" · ")
            val time = when (st.status) {
                Status.PENDING -> ctx.clock(s, sum.projectedEta(st))
                else -> st.completedAt?.let { android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(it)) } ?: ""
            }
            StopAdapter.Row(st, st.id == cur?.id, l1, l2, meta, cond.contains("late"), time)
        }
    }

    /** Scrolls the list so the current stop is visible. */
    fun scrollToCurrent(s: RouteSession) {
        if (s.cursor in s.stops.indices) (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(s.cursor, ctx.dp(8))
    }

    /** Hides the list when the sheet is just a header (no stops). */
    fun listVisible(v: Boolean) { list.visibility = if (v) View.VISIBLE else View.GONE }
}
