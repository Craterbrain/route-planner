package app.routeplanner.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import app.routeplanner.AddressSearch
import app.routeplanner.color
import app.routeplanner.core.PhotonGeocoder
import app.routeplanner.core.Suggestion
import com.google.android.material.R as M
import com.google.android.material.card.MaterialCardView

/**
 * Suggestion list under the search bar. Typing is debounced (400 ms, at
 * least 3 characters), lookups run off the UI thread, stale answers are
 * dropped, and an empty or failed lookup says so instead of staying silent.
 */
class SearchPanel(
    private val act: Activity,
    private val field: EditText,
    private val search: () -> AddressSearch,
    private val onPick: (Suggestion, String) -> Unit,
) {
    val card = MaterialCardView(act).apply {
        radius = act.dpf(20f)
        cardElevation = act.dpf(4f)
        setCardBackgroundColor(act.color(M.attr.colorSurfaceContainerHigh))
        strokeWidth = 0
        visibility = View.GONE
    }
    private val rows = act.vertical(padV = 4)
    private val handler = Handler(Looper.getMainLooper())
    private var token = 0
    private var shown: List<Suggestion> = emptyList()
    private var suppress = false

    init {
        card.addView(rows)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!suppress) schedule(s?.toString().orEmpty(), 400) }
        })
    }

    private val pending = Runnable { lookup(field.text.toString()) }

    private fun schedule(text: String, delay: Long) {
        handler.removeCallbacks(pending)
        token++ // invalidates any lookup in flight
        val q = text.trim()
        if (q.length < PhotonGeocoder.MIN_CHARS) { hide(); return }
        handler.postDelayed(pending, delay)
    }

    /** Enter key: take the top suggestion if there is one, otherwise search now. */
    fun submit() {
        val q = field.text.toString().trim()
        if (q.isEmpty()) return
        val first = shown.firstOrNull()
        if (first != null) pick(first, q) else { handler.removeCallbacks(pending); token++; lookup(q) }
    }

    private fun lookup(text: String) {
        val q = text.trim()
        if (q.length < PhotonGeocoder.MIN_CHARS) { hide(); return }
        val my = ++token
        status("Searching…")
        val s = search()
        Thread {
            val r = s.suggest(q)
            act.runOnUiThread {
                if (my != token || act.isDestroyed || field.text.toString().trim() != q) return@runOnUiThread
                when (r) {
                    is AddressSearch.Outcome.Found -> show(r.items, q)
                    AddressSearch.Outcome.Empty -> status("No matches for “$q”")
                    is AddressSearch.Outcome.Failed -> { status(r.message); s.recheckNetwork() }
                }
            }
        }.start()
    }

    private fun status(text: String) {
        shown = emptyList()
        rows.removeAllViews()
        rows.addFull(act.label(14f, M.attr.colorOnSurfaceVariant).apply {
            this.text = text
            setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(12))
        })
        card.visibility = View.VISIBLE
    }

    private fun show(items: List<Suggestion>, typed: String) {
        shown = items
        rows.removeAllViews()
        for (it in items) {
            val row = act.vertical(padH = 20, padV = 10).apply {
                isClickable = true
                isFocusable = true
                val tv = android.util.TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) background = act.getDrawable(tv.resourceId)
                setOnClickListener { _ -> pick(it, typed) }
            }
            row.addFull(act.label(15f, bold = true, lines = 1).apply { text = it.line1 })
            if (it.line2.isNotEmpty()) row.addFull(act.label(13f, M.attr.colorOnSurfaceVariant, lines = 1).apply { text = it.line2 })
            rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        card.visibility = View.VISIBLE
    }

    private fun pick(s: Suggestion, typed: String) {
        clear()
        onPick(s, typed)
    }

    /** Empties the field and hides the panel without triggering a lookup. */
    fun clear() {
        suppress = true
        field.setText("")
        suppress = false
        handler.removeCallbacks(pending)
        token++
        hide()
    }

    fun hide() {
        shown = emptyList()
        rows.removeAllViews()
        card.visibility = View.GONE
    }

    val isShowing get() = card.visibility == View.VISIBLE
}
