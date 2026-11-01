package app.routeplanner

import android.app.Application
import com.google.android.material.color.DynamicColors

class RoutePlannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this) // first, so a crash during startup is recorded too
        // No-op below Android 12; there the seed colors in themes.xml apply.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
