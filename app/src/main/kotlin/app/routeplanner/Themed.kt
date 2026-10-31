package app.routeplanner

import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

/** App theme (+ dynamic color) for UI built outside an Activity, e.g. the overlay service. */
fun Context.themed(): Context =
    DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(this, R.style.Theme_RoutePlanner))

/** Resolves a theme color attribute such as [com.google.android.material.R.attr.colorPrimary]. */
fun Context.color(attr: Int): Int = MaterialColors.getColor(this, attr, 0)
