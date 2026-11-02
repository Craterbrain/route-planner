package app.routeplanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import app.routeplanner.R
import app.routeplanner.color
import app.routeplanner.core.Status
import app.routeplanner.core.Stop
import com.google.android.material.R as M

/**
 * The stop list. Rows mirror the session's stop order one to one (adapter
 * position == session index), so drag/drop maps straight to a move.
 *
 * Gestures (as in Circuit): swipe left = Delivered (or Undo on a finished
 * stop), swipe right = Navigate, long-press to reorder, tap for details.
 */
class StopAdapter(private val ctx: Context, private val host: RouteHost) : RecyclerView.Adapter<StopAdapter.Holder>() {

    /** Pre-formatted row content (built by [RouteSheet] from the session). */
    data class Row(
        val stop: Stop,
        val current: Boolean,
        val line1: String,
        val line2: String,
        val meta: String,
        val metaIsWarning: Boolean,
        val time: String,
        val pkgs: Int = 0,
    )

    private var rows: MutableList<Row> = mutableListOf()
    var dragging = false
        private set
    private var dragId: String? = null
    private var dragTo = -1

    val touchHelper = ItemTouchHelper(Touch())

    init { setHasStableIds(true) }

    /**
     * Granular updates via DiffUtil. (notifyDataSetChanged while ItemTouchHelper
     * was still animating a swiped row left a stale holder that kept painting
     * an "Undo" strip forever.)
     */
    fun submit(new: List<Row>) {
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = new.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].stop.id == new[n].stop.id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
        })
        rows = new.toMutableList()
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].stop.id.hashCode().toLong()

    class Holder(
        val root: LinearLayout,
        val badge: TextView,
        val line1: TextView,
        val line2: TextView,
        val meta: TextView,
        val idBadge: TextView,
        val pkgBadge: TextView,
        val time: TextView,
        val handle: ImageView,
    ) : RecyclerView.ViewHolder(root)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = ctx
        val root = c.horizontal().apply {
            // Full width: without this RecyclerView sizes the row to its content and the drag handle lands mid-row.
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(c.dp(12), c.dp(10), c.dp(4), c.dp(10))
            minimumHeight = c.dp(64)
            isClickable = true
            isFocusable = true
            // Ripple on tap, drawn above the current-stop highlight.
            val tv = android.util.TypedValue()
            if (c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) foreground = c.getDrawable(tv.resourceId)
        }
        val badge = TextView(c).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(badge, LinearLayout.LayoutParams(c.dp(44), c.dp(44)).apply { marginEnd = c.dp(12) })
        val texts = c.vertical()
        val line1 = c.label(16f, bold = true, lines = 1)
        val line2 = c.label(13f, M.attr.colorOnSurfaceVariant, lines = 1)
        val meta = c.label(12f, M.attr.colorOnSurfaceVariant, lines = 2)
        // The address ID as a badge (tertiary container on its on-color: strong contrast in light and dark), beside the details.
        val idBadge = c.chip(M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer, 15f)
        // Package count as its own chip, in the same weight: ID and "how many to grab" are what a courier looks for first.
        val pkgBadge = c.chip(M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer, 15f)
        val metaRow = c.horizontal(Gravity.CENTER_VERTICAL)
        metaRow.addView(idBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = c.dp(8); topMargin = c.dp(4) })
        metaRow.addView(pkgBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(4) })
        texts.addView(line1); texts.addView(line2); texts.addView(metaRow); texts.addView(meta)
        root.addWeighted(texts)
        val time = c.label(13f, M.attr.colorOnSurfaceVariant).apply { gravity = Gravity.END }
        root.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(8) })
        val handle = ImageView(c).apply {
            setImageResource(R.drawable.ic_drag_handle)
            contentDescription = "Drag to reorder"
            setColorFilter(c.color(M.attr.colorOnSurfaceVariant))
            setPadding(c.dp(10), c.dp(10), c.dp(10), c.dp(10))
        }
        root.addView(handle, LinearLayout.LayoutParams(c.dp(44), c.dp(44)))
        val h = Holder(root, badge, line1, line2, meta, idBadge, pkgBadge, time, handle)
        handle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(h)
            false
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = rows[position]
        val s = r.stop
        val colors = ctx.badgeColors(s, r.current)
        h.badge.text = badgeText(s)
        h.badge.setTextColor(colors.text)
        h.badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors.fill) }
        h.line1.text = r.line1
        h.line2.text = r.line2
        h.line2.visibility = if (r.line2.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        h.meta.text = r.meta
        h.meta.visibility = if (r.meta.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        h.meta.setTextColor(ctx.color(if (r.metaIsWarning) M.attr.colorError else M.attr.colorOnSurfaceVariant))
        h.idBadge.text = "ID ${s.entryNo}"
        h.pkgBadge.text = totalPackages(r.pkgs)
        h.pkgBadge.visibility = if (r.pkgs > 0) android.view.View.VISIBLE else android.view.View.GONE
        h.time.text = r.time
        val finished = s.status != Status.PENDING
        h.root.alpha = if (finished) 0.6f else 1f
        h.root.background = if (r.current) ctx.roundRect(M.attr.colorSecondaryContainer, 16) else null
        h.handle.visibility = android.view.View.GONE // reorder by long-press instead
        h.root.contentDescription = "Stop ${s.optimizedIndex}, ID ${s.entryNo}, ${s.address}, ${s.status.name.lowercase()}"
        h.root.setOnClickListener { host.openStop(s.id) }
    }

    private inner class Touch : ItemTouchHelper.Callback() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dpf(15f); isFakeBoldText = true }

        override fun isLongPressDragEnabled() = true

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            val pos = vh.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return 0
            val pending = rows[pos].stop.status == Status.PENDING
            val drag = if (pending) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
            return makeMovementFlags(drag, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
        }

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.adapterPosition
            val b = to.adapterPosition
            if (a == RecyclerView.NO_POSITION || b == RecyclerView.NO_POSITION) return false
            rows.add(b, rows.removeAt(a))
            notifyItemMoved(a, b)
            dragId = rows[b].stop.id
            dragTo = b
            return true
        }

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) { dragging = true; dragId = null; dragTo = -1 }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            if (!dragging) return
            dragging = false
            val id = dragId
            dragId = null
            if (id != null && dragTo >= 0) host.moveStop(id, dragTo)
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val pos = vh.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val s = rows[pos].stop
            notifyItemChanged(pos) // spring the row back; the session update re-renders it
            when (direction) {
                ItemTouchHelper.LEFT -> if (s.status == Status.PENDING) host.deliver(s.id) else host.undo(s.id)
                ItemTouchHelper.RIGHT -> host.navigate(s.id)
            }
        }

        override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.35f

        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, active: Boolean) {
            val pos = vh.adapterPosition
            // Only paint behind a row that is really being swiped and still in the list.
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f && pos != RecyclerView.NO_POSITION && pos < rows.size && vh.itemView.parent != null) {
                val v = vh.itemView
                val pending = rows[pos].stop.status == Status.PENDING
                val left = dX < 0
                val (bg, fg, text) = when {
                    !left -> Triple(M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer, "Navigate")
                    pending -> Triple(M.attr.colorPrimary, M.attr.colorOnPrimary, "Delivered")
                    else -> Triple(M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer, "Undo")
                }
                paint.color = ctx.color(bg)
                if (left) c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), paint)
                else c.drawRect(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat(), paint)
                paint.color = ctx.color(fg)
                val y = v.top + v.height / 2f - (paint.descent() + paint.ascent()) / 2
                val pad = ctx.dpf(24f)
                if (left) c.drawText(text, v.right - pad - paint.measureText(text), y, paint)
                else c.drawText(text, v.left + pad, y, paint)
            }
            super.onChildDraw(c, rv, vh, dX, dY, actionState, active)
        }
    }
}
