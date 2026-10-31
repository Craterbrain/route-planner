package app.routeplanner.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.routeplanner.color
import app.routeplanner.core.*
import com.google.android.material.R as M
import com.google.android.material.button.MaterialButton
import java.util.Date
import java.util.Locale

/** Small helpers for building Material 3 views in code. All colors come from theme attributes. */

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun Context.label(size: Float, colorAttr: Int = M.attr.colorOnSurface, bold: Boolean = false, lines: Int = 0) = TextView(this).apply {
    textSize = size
    setTextColor(color(colorAttr))
    if (bold) setTypeface(typeface, Typeface.BOLD)
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}

fun Context.button(text: String, style: Int = M.attr.materialButtonStyle, icon: Int? = null, onClick: () -> Unit) =
    MaterialButton(this, null, style).apply {
        this.text = text
        icon?.let { setIconResource(it) }
        setOnClickListener { onClick() }
    }

/** Round icon-only button (e.g. mic, overflow, prev/next). */
fun Context.iconButton(icon: Int, description: String, style: Int = M.attr.materialIconButtonStyle, onClick: () -> Unit) =
    MaterialButton(this, null, style).apply {
        setIconResource(icon)
        contentDescription = description
        iconPadding = 0
        setOnClickListener { onClick() }
    }

/**
 * Fits a button into a tight weighted slot: one line, small side padding, and
 * text that shrinks (down to 11sp) instead of wrapping at large font scales.
 */
fun Context.compact(b: MaterialButton) = b.apply {
    isSingleLine = true
    maxLines = 1
    setPadding(dp(8), paddingTop, dp(8), paddingBottom)
    iconPadding = dp(4)
    minWidth = 0
    minimumWidth = 0
    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 11, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
}

/** Tonal button = filled button on secondaryContainer (Material 3 "filled tonal"). */
fun Context.tonal(b: MaterialButton) = b.apply {
    backgroundTintList = ColorStateList.valueOf(color(M.attr.colorSecondaryContainer))
    setTextColor(color(M.attr.colorOnSecondaryContainer))
    iconTint = ColorStateList.valueOf(color(M.attr.colorOnSecondaryContainer))
}

fun Context.vertical(padH: Int = 0, padV: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
}

fun Context.horizontal(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun ViewGroup.addWeighted(v: View, weight: Float = 1f, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, marginDp: Int = 0) {
    val m = context.dp(marginDp)
    addView(v, LinearLayout.LayoutParams(0, height, weight).apply { setMargins(m, 0, m, 0) })
}

fun ViewGroup.addFull(v: View, topDp: Int = 0, bottomDp: Int = 0) {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(topDp); bottomMargin = context.dp(bottomDp)
    })
}

/** Rounded rectangle filled with a theme color. */
fun Context.roundRect(colorAttr: Int, radiusDp: Int) = GradientDrawable().apply {
    cornerRadius = dpf(radiusDp.toFloat())
    setColor(color(colorAttr))
}

/** Colors for a stop's number badge (and map pin): pending, current, done, failed. */
data class BadgeColors(val fill: Int, val text: Int)

fun Context.badgeColors(s: Stop, isCurrent: Boolean): BadgeColors = when {
    s.status == Status.FAILED || s.status == Status.SKIPPED -> BadgeColors(color(M.attr.colorError), color(M.attr.colorOnError))
    s.status == Status.COMPLETED -> BadgeColors(color(M.attr.colorSurfaceVariant), color(M.attr.colorOnSurfaceVariant))
    isCurrent -> BadgeColors(color(M.attr.colorTertiary), color(M.attr.colorOnTertiary))
    else -> BadgeColors(color(M.attr.colorPrimary), color(M.attr.colorOnPrimary))
}

/** Text shown inside the badge: the stop number, or a mark once handled. */
fun badgeText(s: Stop): String = when (s.status) {
    Status.COMPLETED -> "✓"
    Status.FAILED -> "!"
    Status.SKIPPED -> "–"
    Status.PENDING -> s.optimizedIndex.toString()
}

/** Wall-clock time (user's 12/24 h setting) of [sec] on the session's route clock. */
fun Context.clock(s: RouteSession, sec: Int): String =
    android.text.format.DateFormat.getTimeFormat(this).format(Date(s.startMs + sec * 1000L))

/** Miles in the US, UK, Liberia and Myanmar; kilometres elsewhere. */
fun imperialUnits(): Boolean = Locale.getDefault().country in setOf("US", "GB", "LR", "MM")

val FAIL_LABELS = linkedMapOf(
    FailReason.RECIPIENT_MISSING to "Recipient not home",
    FailReason.WRONG_ADDRESS to "Can't find address",
    FailReason.ACCESS_CODE_NEEDED to "Access code needed",
    FailReason.NO_PARKING to "No parking",
    FailReason.NO_TIME to "No time",
    FailReason.OTHER to "Other",
)

fun placementLabel(p: Placement) = when (p) {
    Placement.FIRST -> "First"
    Placement.AUTO -> "Auto"
    Placement.LAST -> "Last"
}

/** "09:00–11:00", "after 09:00", "before 11:00". Open ends are stored as very large/small values. */
fun Context.windowLabel(s: RouteSession, w: LongRange): String {
    val open = w.first > WINDOW_OPEN_START
    val close = w.last < WINDOW_OPEN_END
    return when {
        open && close -> "${clock(s, w.first.toInt())}–${clock(s, w.last.toInt())}"
        open -> "after ${clock(s, w.first.toInt())}"
        close -> "before ${clock(s, w.last.toInt())}"
        else -> "Anytime"
    }
}

/** Sentinels for a one-sided window ("10am and Anytime" in Circuit's terms). */
const val WINDOW_OPEN_START = -10_000_000L
const val WINDOW_OPEN_END = 10_000_000L
