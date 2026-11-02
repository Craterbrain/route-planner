package app.routeplanner.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import app.routeplanner.AddressSearch
import app.routeplanner.R
import app.routeplanner.color
import app.routeplanner.core.AddressMatch
import app.routeplanner.core.LatLng
import app.routeplanner.core.PhotonGeocoder
import app.routeplanner.core.formatDistance
import app.routeplanner.core.haversine
import app.routeplanner.core.Suggestion
import app.routeplanner.core.SuggestRanker
import app.routeplanner.core.SuggestSource
import com.google.android.material.R as M
import com.google.android.material.card.MaterialCardView

/**
 * Suggestion list under the search bar. Local rows (recents, this route's stops,
 * saved drop-offs) appear on every keystroke; network/offline rows follow after
 * a 350 ms pause (3+ characters), merged below them. Stale answers are dropped.
 * Every row has a fill-in arrow that copies its text into the box without
 * picking it. A caption says which source answered.
 */
class SearchPanel(
    private val act: Activity,
    private val field: EditText,
    private val search: () -> AddressSearch,
    /** Adds the typed text as a stop even though nothing confirmed it (placed approximately; the driver fixes the pin). */
    private val onAddAnyway: (String) -> Unit,
    /** Where distances on the rows are measured from (the route start), or null. */
    private val origin: () -> LatLng? = { null },
    private val onPick: (Suggestion, String) -> Unit,
) {
    companion object { const val DEBOUNCE_MS = 350L }

    val card = MaterialCardView(act).apply {
        radius = act.dpf(20f)
        cardElevation = act.dpf(12f) // above the bottom sheet (6 dp), so suggestions show over an expanded sheet
        setCardBackgroundColor(act.color(M.attr.colorSurfaceContainerHigh))
        strokeWidth = 0
        visibility = View.GONE
    }
    private val rows = act.vertical(padV = 4)
    private val handler = Handler(Looper.getMainLooper())
    private var token = 0
    private var shown: List<Suggestion> = emptyList()
    private var local: List<Suggestion> = emptyList()
    private var suppress = false

    init {
        card.addView(rows)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!suppress) onTyped(s?.toString().orEmpty()) }
        })
    }

    private val pending = Runnable { lookup(field.text.toString()) }

    private fun onTyped(text: String) {
        handler.removeCallbacks(pending)
        token++ // invalidates any lookup in flight
        val q = text.trim()
        if (q.length < 2) { hide(); return }
        local = runCatching { search().local(q) }.getOrDefault(emptyList())
        if (q.length < PhotonGeocoder.MIN_CHARS) { if (local.isEmpty()) hide() else show(local, q, null, null); return }
        show(local, q, null, "Searching…")
        handler.postDelayed(pending, DEBOUNCE_MS)
    }

    /** Enter key: take the top suggestion with a position if there is one, otherwise search now. */
    fun submit() {
        val q = field.text.toString().trim()
        if (q.isEmpty()) return
        val first = shown.firstOrNull { it.point != null }
        if (first != null) pick(first, q) else { handler.removeCallbacks(pending); token++; lookup(q) }
    }

    private fun lookup(text: String) {
        val q = text.trim()
        if (q.length < PhotonGeocoder.MIN_CHARS) return
        val my = ++token
        val s = search()
        val localNow = local
        Thread {
            val r = s.suggest(q)
            act.runOnUiThread {
                if (my != token || act.isDestroyed || field.text.toString().trim() != q) return@runOnUiThread
                when (r) {
                    is AddressSearch.Outcome.Found -> {
                        // An address (starts with a house number) that none of the rows confirms can still be added as typed.
                        val unconfirmed = q.firstOrNull()?.isDigit() == true && r.items.none { AddressMatch.matches(q, it) }
                        show(SuggestRanker.merge(localNow, r.items), q, captionFor(r.source), r.note, anyway = unconfirmed)
                    }
                    is AddressSearch.Outcome.Empty -> show(localNow, q, null, listOfNotNull(r.note, "No matches for “$q”").joinToString("\n"), anyway = true)
                    is AddressSearch.Outcome.Failed -> { show(localNow, q, null, r.message, anyway = true); s.recheckNetwork() }
                }
            }
        }.start()
    }

    private fun captionFor(src: SuggestSource) = when (src) {
        SuggestSource.GOOGLE -> "Google"
        SuggestSource.OFFLINE -> "Offline address pack"
        SuggestSource.DEVICE -> "Phone geocoder"
        SuggestSource.OSM -> "OSM (Photon)"
        SuggestSource.LOCAL -> "Recent"
    }

    private fun status(text: String) = act.label(13f, M.attr.colorOnSurfaceVariant).apply {
        this.text = text
        setPadding(act.dp(20), act.dp(8), act.dp(20), act.dp(8))
    }

    private fun show(items: List<Suggestion>, typed: String, caption: String?, message: String?, anyway: Boolean = false) {
        shown = items
        rows.removeAllViews()
        for (it in items) rows.addView(row(it, typed), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        message?.let { rows.addFull(status(it)) }
        if (anyway && typed.length >= 5) rows.addFull(addAnywayRow(typed))
        caption?.let { rows.addFull(act.label(11f, M.attr.colorOnSurfaceVariant).apply {
            text = "Suggestions: $it"
            setPadding(act.dp(20), act.dp(2), act.dp(20), act.dp(8))
        }) }
        card.visibility = if (rows.childCount > 0) View.VISIBLE else View.GONE
    }

    /** Last row when nothing confirms the typed address: add it as typed. */
    private fun addAnywayRow(typed: String): View {
        val row = act.horizontal().apply {
            setPadding(act.dp(20), act.dp(10), act.dp(16), act.dp(10))
            isClickable = true
            isFocusable = true
            val tv = android.util.TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) background = act.getDrawable(tv.resourceId)
            setOnClickListener { clear(); onAddAnyway(typed) }
        }
        row.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(act.color(M.attr.colorPrimary))
        }, LinearLayout.LayoutParams(act.dp(24), act.dp(24)).apply { marginEnd = act.dp(16) })
        val texts = act.vertical()
        texts.addFull(act.label(15f, M.attr.colorPrimary, bold = true, lines = 1).apply { text = "Add “$typed” anyway" })
        texts.addFull(act.label(12f, M.attr.colorOnSurfaceVariant).apply { text = "Placed at an estimated spot; you set the pin on the map." })
        row.addWeighted(texts)
        return row
    }

    /** "742 mi" from the route start, so a namesake in another state is obvious; nothing under ~1 mi. */
    private fun distanceNote(s: Suggestion): String {
        val from = origin() ?: return ""
        val p = s.point ?: return ""
        val m = haversine(from, p)
        return if (m < 1500) "" else formatDistance(m.toInt(), imperialUnits()) + " away"
    }

    private fun row(s: Suggestion, typed: String): View {
        val row = act.horizontal().apply {
            setPadding(act.dp(20), act.dp(8), act.dp(4), act.dp(8))
            isClickable = true
            isFocusable = true
            val tv = android.util.TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) background = act.getDrawable(tv.resourceId)
            setOnClickListener { if (s.point != null) pick(s, typed) else fill(s) }
        }
        val texts = act.vertical()
        texts.addFull(act.label(15f, bold = true, lines = 1).apply { text = s.title })
        val sub = listOf(
            s.subtitle,
            if (s.source == SuggestSource.LOCAL) "recent" else "",
            if (s.approximate && !s.subtitle.contains("Estimated")) "estimated position" else "",
            distanceNote(s),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) texts.addFull(act.label(13f, if (s.approximate) M.attr.colorTertiary else M.attr.colorOnSurfaceVariant, lines = 1).apply { text = sub })
        row.addWeighted(texts)
        row.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_north_west)
            setColorFilter(act.color(M.attr.colorOnSurfaceVariant))
            contentDescription = "Fill in “${s.title}”"
            setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12))
            isClickable = true
            setOnClickListener { fill(s) }
        }, LinearLayout.LayoutParams(act.dp(48), act.dp(48)))
        return row
    }

    /** Copies the row's text into the box (cursor at the end) and refreshes suggestions. */
    private fun fill(s: Suggestion) {
        val text = if (s.point == null || s.source == SuggestSource.GOOGLE && s.subtitle.isEmpty()) s.title else s.label
        field.setText(text)
        field.setSelection(field.text.length)
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
