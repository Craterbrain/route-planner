# route-planner

A Circuit-style delivery route optimizer, targeting **Android**. Design brief: [docs/idea.md](docs/idea.md).

## Layout

| Path | What |
|---|---|
| `core/` | Pure-Kotlin/JVM engine (no Android deps): manifest import, solver, route state machine, nav deep links. JUnit tests. |
| `app/` | Android app (Kotlin, platform Views + Material 3; deps: `core-ktx`, `material`): CSV import, route list, package-finder card, action bar, floating overlay. |
| `prototype/` | Original zero-dep Node version of the core, kept as a reference. |

## Build

System JDK 27 breaks Gradle here; use JDK 17:

```sh
J=-Dorg.gradle.java.home=$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2
./gradlew :core:test :app:assembleDebug $J
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Manifest CSV columns: `address,lat,lng,package,zone,priority,window_start,window_end,service_min,notes`
(example: `prototype/examples/manifest.csv`).

## Status vs. the idea doc

| Idea point | Status |
|---|---|
| 1. Door target + street access point per stop; driver pin adjust | model + `adjustAccessPoint`; **no geocoder or pin-drag UI yet** |
| 2. Map (MapLibre, offline tiles) | not started (the one planned heavy dependency) |
| 3. VRPTW | single vehicle: priority, windows, service time, ETAs (nearest-feasible + 2-opt/or-opt). Matrix is offline haversine behind `MatrixProvider`; OSRM provider next |
| 4. Packages, truck zones, indexes, CSV import | done; OCR scanner not started |
| 5. Deep links | Google/Waze launched from the app (Apple Maps link exists in core, iOS only) |
| 6. Action bar + overlay | in-app bar and a `SYSTEM_ALERT_WINDOW` foreground-service overlay (◄ ✓ ✗ ►); fail-reason dialog; auto-navigate; route persists across process death |
| Live injection | in core (`RouteSession.inject`); no UI yet |
| Proof of delivery, voice input | not started |

## Not yet verified on a device

The APK builds but has not been run on a phone or emulator. First thing to check: overlay permission flow, and that `✓` with auto-navigate launches Maps from over another app.

## Roadmap

1. Run on a device; fix what breaks.
2. Geocoding adapter (Mapbox/Google) + per-address pin overrides in a local DB.
3. OSRM/VROOM provider behind `MatrixProvider`.
4. MapLibre map, offline regions, pin-drag.
5. Add-stop UI (live injection), proof of delivery (photo/signature/note).
6. Label OCR (ML Kit), voice address entry.
7. Server + sync (Postgres/PostGIS).

## Theming

Material 3 `DayNight` (`app/src/main/res/values/themes.xml`): follows the system light/dark setting, and on Android 12+ `RoutePlannerApp` applies the user's Material You dynamic colors (`DynamicColors`). Below Android 12 the `seed_*` palette in `values{,-night}/colors.xml` is used. UI code should read colors from theme attributes (`color(M.attr.colorPrimary)`), never hard-code them; code outside an Activity (the overlay service) uses `Context.themed()` from `Themed.kt`.
