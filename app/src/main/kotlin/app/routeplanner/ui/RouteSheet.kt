package app.routeplanner.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    /** Left and right of the drag handle, in the same row: Navigate / Stop / route menu / summary. */
    private val barStart = ctx.horizontal()
    private val barEnd = ctx.horizontal()
    private val handleBar = FrameLayout(ctx).apply {
        addView(handle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(barStart, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = ctx.dp(16) })
        addView(barEnd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = ctx.dp(8) })
    }
    private val adapter = StopAdapter(ctx, host)
    val list = RecyclerView(ctx)
    /** The route start, shown as the first row above the stops. */
    private val startRow = ctx.vertical()
    /** Package finder expanded? Collapsed by default to keep the card short; kept across stops. */
    private var finderOpen = false
    private var finderAnim: android.animation.ValueAnimator? = null

    init {
        val r = ctx.dpf(28f)
        view.background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(ctx.color(M.attr.colorSurfaceContainerLow))
        }
        ViewCompat.setElevation(view, ctx.dpf(6f))
        view.isClickable = true // don't let taps fall through to the map
        view.addView(handleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
    fun setBottomInset(px: Int) { inset = px; applyListPadding() }

    private var inset = 0
    private var overflow = 0

    /**
     * The sheet is as tall as its parent but sits below the search bar when expanded, so its bottom hangs off the screen
     * by that much; pad the list by the overhang so the last stops can scroll into view.
     */
    fun setOverflow(px: Int) { if (px != overflow) { overflow = px; applyListPadding() } }

    private fun applyListPadding() = list.setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(8) + inset + overflow)

    fun render(s: RouteSession?, start: RouteStart?, end: EndMode) {
        finderAnim?.cancel()
        header.removeAllViews()
        // The start row is planning information; while driving it only gets in the way (and sat under the nav bar).
        startRow.visibility = if (s != null && s.started && !s.done) View.GONE else View.VISIBLE
        barStart.removeAllViews()
        barEnd.removeAllViews()
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

    /** A bold title with the route menu (⋮: New route, Save route, Open route) on the right. */
    private fun titleRow(title: String, topDp: Int = 0, bottomDp: Int = 0, menu: Boolean = true) {
        val row = ctx.horizontal()
        row.addWeighted(ctx.label(22f, bold = true).apply { text = title })
        if (menu) row.addView(routeMenuButton())
        header.addFull(row, topDp = topDp, bottomDp = bottomDp)
    }

    private fun routeMenuButton() = ctx.iconButton(R.drawable.ic_more_vert, "Route menu") {}.also { b ->
        b.setOnClickListener { host.routeMenu(b) }
    }

    private fun emptyState() {
        titleRow("Plan your route", topDp = 4, bottomDp = 4)
        // Actions first, so they stay clear of the gesture bar when the sheet is collapsed.
        val row = ctx.horizontal()
        row.addWeighted(ctx.compact(ctx.button("Import spreadsheet", M.attr.materialButtonOutlinedStyle) { host.importCsv() }), marginDp = 4)
        row.addWeighted(ctx.compact(ctx.button("Add stop", icon = R.drawable.ic_add) { host.focusSearch() }), marginDp = 4)
        header.addFull(row, bottomDp = 8)
        header.addFull(ctx.label(14f, M.attr.colorOnSurfaceVariant).apply {
            text = "Add stops with the search bar (type, speak or scan a label); results near your start come first. You can also long-press the map to drop one, or import a spreadsheet of addresses."
        }, bottomDp = 12)
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
        // Time and distance beside the handle on the left; finish time and the route menu on the right.
        titleRow(if (sum.total == 1) "1 stop" else "${sum.total} stops", menu = false)
        barStart.addView(ctx.label(14f, M.attr.colorOnSurfaceVariant).apply {
            text = listOfNotNull(
                formatDuration(sum.remainingSec),
                sum.totalMeters.takeIf { it > 0 }?.let { formatDistance(it, imperialUnits()) },
            ).joinToString(" · ")
        })
        barEnd.addView(ctx.label(14f, M.attr.colorOnSurfaceVariant).apply { text = "done by ${ctx.clock(s, sum.finishSec)}" })
        barEnd.addView(routeMenuButton())
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
        // Navigate on the left of the handle; Stop (back to planning, progress kept) and the route menu on the right.
        // Not compact(): its auto-sizing text needs a fixed-width slot and clips a wrap-content button.
        barStart.addView(ctx.tonal(ctx.button("Navigate", icon = R.drawable.ic_navigation) { host.navigate(c.id) }))
        barEnd.addView(ctx.button("Stop", M.attr.materialButtonOutlinedStyle, icon = R.drawable.ic_stop) { host.stopRoute() })
        barEnd.addView(routeMenuButton())
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

        // [<]  Stop 3 of 12 · ETA 10:42 · ID 7 · 2 pkgs  [>]
        //      9 left · 1 h 05 min · 14.2 mi · done by 3:10 PM
        val top = ctx.horizontal()
        val chevron = LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48))
        top.addView(ctx.iconButton(R.drawable.ic_chevron_left, "Previous stop") { host.prev() }, chevron)
        val pendingAfter = s.stops.count { it.status == Status.PENDING }
        val info = ctx.vertical().apply { setPadding(ctx.dp(4), 0, ctx.dp(4), 0) }
        info.addFull(ctx.label(14f, onCard, bold = true).apply {
            text = "Stop ${c.optimizedIndex} of ${s.stops.size}" +
                (if (c.status == Status.PENDING) " · ETA ${ctx.clock(s, sum.projectedEta(c))}" else " · ${c.status.name.lowercase()}") +
                " · ID ${c.entryNo} · ${totalPackages(s.finder(c).size)}"
        })
        info.addFull(ctx.label(13f, onCard).apply { text = "$pendingAfter left · ${summaryLine(s, sum, remainingOnly = true)}" })
        top.addWeighted(info)
        top.addView(ctx.iconButton(R.drawable.ic_chevron_right, "Next stop") { host.next() }, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
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
        // Nothing to find (no shelf set, no scanned label): the row would only say "no location set", so leave it out.
        if (pkgs.any { it.truckZone != null || Barcodes.lastFour(it) != null }) {
            val pf = ctx.vertical(padH = 12, padV = 8).apply { background = ctx.roundRect(M.attr.colorSurface, 14) }
            val head = ctx.horizontal().apply { isClickable = true }
            head.addWeighted(ctx.label(11f, M.attr.colorOnSurfaceVariant, bold = true).apply { text = "PACKAGE FINDER" })
            val chevron = android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(ctx.color(M.attr.colorOnSurfaceVariant))
            }
            head.addView(chevron, LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
            pf.addFull(head)
            val lines = ctx.vertical()
            for (p in pkgs) {
                // "45-1 · Back Left Shelf" with the last four of its tracking number as a badge on the right.
                val line = ctx.horizontal()
                line.addWeighted(ctx.label(15f).apply { text = "Pkg ${p.id}  ·  ${p.truckZone ?: "no shelf set"}" })
                Barcodes.lastFour(p)?.let { line.addView(ctx.lastFourBadge(it)) }
                lines.addFull(line, topDp = 4)
            }
            pf.addFull(lines)
            fun sync() {
                lines.visibility = if (finderOpen) View.VISIBLE else View.GONE
                chevron.rotation = if (finderOpen) 270f else 90f
                head.contentDescription = "Package finder, ${pkgs.size} packages, " + if (finderOpen) "tap to collapse" else "tap to expand"
            }
            // Animate the list's height itself: the header (and so the sheet's peek) follows every frame, with no stale size.
            head.setOnClickListener {
                finderAnim?.cancel()
                finderOpen = !finderOpen
                head.contentDescription = "Package finder, ${pkgs.size} packages, " + if (finderOpen) "tap to collapse" else "tap to expand"
                lines.measure(
                    View.MeasureSpec.makeMeasureSpec((pf.width - pf.paddingLeft - pf.paddingRight).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val full = lines.measuredHeight
                val from = if (lines.visibility == View.VISIBLE && lines.height > 0) lines.height else 0
                val to = if (finderOpen) full else 0
                lines.visibility = View.VISIBLE
                finderAnim = android.animation.ValueAnimator.ofInt(from, to).apply {
                    duration = 200
                    addUpdateListener {
                        lines.layoutParams.height = it.animatedValue as Int
                        lines.requestLayout()
                        chevron.rotation = 90f + 180f * (it.animatedValue as Int) / full.coerceAtLeast(1)
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        private var cancelled = false
                        override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            if (cancelled) return
                            lines.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            sync()
                        }
                    })
                    start()
                }
            }
            sync()
            body.addFull(pf, topDp = 8)
        }

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
        body.addFull(actions, topDp = 8)
        header.addFull(card, topDp = 0, bottomDp = 8)
    }

    private fun finishedHeader(s: RouteSession, sum: RouteSummary) {
        titleRow("Route complete")
        header.addFull(ctx.label(14f, M.attr.colorOnSurfaceVariant).apply {
            text = listOfNotNull(
                "${sum.delivered} delivered",
                sum.failed.takeIf { it > 0 }?.let { "$it failed" },
                sum.totalMeters.takeIf { it > 0 }?.let { formatDistance(it, imperialUnits()) },
            ).joinToString(" · ")
        })
        val row = ctx.horizontal()
        row.addWeighted(ctx.compact(ctx.button("New route", M.attr.materialButtonOutlinedStyle) { host.newRoute() }), marginDp = 4)
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
                    totalPackages(p.size) + if (zones.isEmpty()) "" else " (" + zones.joinToString(", ") + ")"
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
