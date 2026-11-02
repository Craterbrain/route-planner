package app.routeplanner

import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

/** App theme (+ dynamic color) for UI built outside an Activity, e.g. the overlay service. */
fun Context.themed(): Context =
    DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(ThemeMode.wrap(this), R.style.Theme_RoutePlanner))

/** Resolves a theme color attribute such as [com.google.android.material.R.attr.colorPrimary]. */
fun Context.color(attr: Int): Int = MaterialColors.getColor(this, attr, 0)

/** The user's light / dark / system choice (Settings → Appearance), applied by overriding the night bit of the context's configuration. */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun wrap(base: Context): Context {
        val mode = base.getSharedPreferences("route-planner", Context.MODE_PRIVATE).getString("themeMode", SYSTEM)
        val night = when (mode) {
            LIGHT -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            DARK -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val cfg = android.content.res.Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }
}
