package app.routeplanner.core

enum class NavApp { GOOGLE, APPLE, WAZE }

/** Deep link that starts turn-by-turn to [p] in the driver's preferred app. */
fun navLink(app: NavApp, p: LatLng): String = when (app) {
    NavApp.GOOGLE -> "google.navigation:q=${p.lat},${p.lng}&mode=d"
    NavApp.APPLE -> "http://maps.apple.com/?daddr=${p.lat},${p.lng}&dirflg=d"
    NavApp.WAZE -> "https://waze.com/ul?ll=${p.lat},${p.lng}&navigate=yes"
}
