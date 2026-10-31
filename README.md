# route-planner

A Circuit-style delivery route optimizer, targeting **Android**. Design brief: [docs/idea.md](docs/idea.md). UI/UX research on Circuit (now Spoke Route Planner) and the feature checklist: [docs/circuit-research.md](docs/circuit-research.md).

## Layout

| Path | What |
|---|---|
| `core/` | Pure-Kotlin/JVM engine (no Android deps): manifest import, solver (first/last, windows, route clock), route state machine and edits (move, make next, reverse, remove), route summary, Photon geocoder, nav deep links. JUnit tests. |
| `app/` | Android app (Kotlin, platform Views + Material 3; deps: `core-ktx`, `material`, `valhalla-mobile`, `maplibre`). `MainActivity` (screen wiring), `RouteActions` (import / add / optimize off the UI thread), `AddressSearch` (geocoder chain), `NetStatus` (reachability), `SessionStore`, `Prefs`, `Routing`, `OverlayService` (floating button); `ui/`: `RouteMap` (MapLibre pins + diagnostics), `SearchPanel` (suggestions), `RouteSheet` + `StopAdapter` (bottom sheet, next-stop card, swipe/drag list), `StopDetailSheet`, `PermissionsSheet`, `Dialogs`, `Ui` helpers. |
| `tools/` | `build-tiles.sh`: builds the on-device routing data from an OSM extract (Docker/Podman). |
| `prototype/` | Original zero-dep Node version of the core, kept as a reference. |

## Build

System JDK 27 breaks Gradle here; use JDK 17. (Kotlin 2.3 and compileSdk 36 are required by `valhalla-mobile`.)

```sh
J=-Dorg.gradle.java.home=$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2
./gradlew :core:test :app:assembleDebug $J
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Manifest CSV columns: `address,lat,lng,package,zone,priority,window_start,window_end,service_min,notes`
(example: `prototype/examples/manifest.csv`). `priority`: `yes`/`first` = visit first, `last` = visit last, blank = optimizer decides.

## The app (Circuit-style flow)

- **Starting point first:** until the route has a start, the sheet shows a "Set your starting point" card: **Use my current location** (fresh fix from the platform `LocationManager`: GPS/network/fused, no Play services; accuracy and age shown, Refresh button), **Search an address** (the search bar switches to "Search your starting address"), or **Pick on the map** (tap, or long-press → "Set as starting point"). Import, Optimize and Start route redirect to the card until then. The start is a distinct "S" pin on the map and the first row above the stop list (tap to change it, or to choose **Route ends: at last stop / back at start**). It is saved as the default for new routes and inside the saved route.
- **Depot:** optimizing, ETAs and the road-time matrix start from the route start (matrix index 0). Once you've finished a stop, re-planning starts from your current position (else the last finished stop). Round trips add the drive back to the finish time and distance.
- **Search bias:** address search (search bar, CSV import) is biased to the start: the platform geocoder looks in a ~55 km box around it first, and Photon gets `lat`/`lon` with `zoom=10` and `location_bias_scale=0.1`.
- **Route screen:** full-screen map with numbered stop pins (current stop larger, in the tertiary color; delivered dimmed with ✓; failed in red with !) and a dashed line through the remaining stops in order. Pins are drawn as bitmaps, so they show even when the basemap can't load (then the map is a plain themed background). A small **map status chip** under the search bar says what the map is doing ("loading basemap…", "style loaded · tiles ok", "no network access · pins only · tap to retry", "N tile errors"); MapLibre and network problems are also logged under the logcat tag `RoutePlanner`.
- **Search bar:** suggestions appear under it as you type (400 ms debounce, 3+ characters, stale lookups dropped). Lookup chain: the phone's geocoder if it has one and it answers, else **Photon** (OpenStreetMap, no key; URL configurable in Settings). No results or no network say so ("No matches", "Couldn't reach address search", "No network access…"). The mic fills the bar by voice. The ⋮ menu has Import spreadsheet, Optimize, Update times, Reverse, Show whole route, Floating button (on/off), Permissions & network, Settings and Clear route. Long-press the map to drop a stop or move the current stop's drop-off pin.
- **Floating button (overlay):** a round button over Google Maps / Waze; drag it (it snaps to the nearest edge, position remembered), tap to expand: current stop, ◄ (previous stop, set back to pending) ✓ Delivered ✗ Failed ► Next, Open app, Close. It collapses after Delivered/Failed/Open app, hides while Route Planner itself is on screen, and its notification has a Stop action.
- **Permissions & network sheet:** shown on first launch and from ⋮ or Settings. One row each for Network (actually tests the map and geocoder hosts; explains GrapheneOS's per-app Network toggle with a button to App info), Location, Notifications (Android 13+), Display over other apps (opens the system screen; the floating button starts only once it's granted), and optional battery-optimization exemption. Re-checked every time the app resumes; nothing blocks the app.
- **Bottom sheet:** empty state (import / add); planning (stop count, time, distance, "done by", late warning, **Optimize** and **Start route**); driving (a next-stop card with ETA, address, first/last and window, notes, **package finder**, **Navigate**, **Failed** / **Proof** / **Delivered**, and ◄ ► to browse without changing anything); finished (summary + new route). Below it is the stop list: swipe left = Delivered (Undo on finished stops), swipe right = Navigate, drag the handle to reorder, tap for details.
- **Stop details:** Navigate / Failed / Delivered (or Undo); proof shown after delivery; Order First/Auto/Last; arrival time (after / before / anytime); time at stop; notes; per-package truck location (Front/Middle/Back · Left/Right · Floor/Shelf + text); "Adjust pin on map" (saved per address); Make next, Move to position, Work on this stop now, Remove.
- **Times:** every route has a clock (`RouteSession.startMs`). Imported HH:MM windows are relative to import time; re-planning mid-route keeps windows meaningful. Edits re-compute ETAs in place; Optimize re-orders the remaining stops from your location.

## Status vs. the idea doc

| Idea point | Status |
|---|---|
| 1. Door target + street access point; geocoding; driver pin adjust | Geocoding chain platform `Geocoder` → Photon for the search bar, long-press reverse lookup and CSV import. Drop-off pins: **Adjust pin on map** or long-press → saved in `AddressMemory` and applied on future imports/adds. |
| 2. Map (MapLibre, offline tiles) | MapLibre map with OpenFreeMap tiles (ambient cache only). Offline region download not started. Map can be turned off in Settings. |
| 3. VRPTW | single vehicle: First/Auto/Last, windows (incl. one-sided), service time, ETAs, distances. Road times: on-device Valhalla (import tiles in Settings) → optional server → straight-line fallback |
| 4. Packages, truck zones, indexes, CSV import | done; truck zone editable per package; barcode/OCR scanner not started |
| 5. Deep links | Google/Waze launched from the app, the card, list swipe, or auto-navigate |
| 6. Action bar + overlay | in-app next-stop card + `SYSTEM_ALERT_WINDOW` overlay (◄ ✓ ✗ ►); fail reasons; auto-navigate; route persists across process death |
| Live injection | add stop any time (search, voice, long-press): appended with fresh ETAs, then **Optimize** from the snackbar or menu |
| Proof of delivery | **Proof** button / long-press **Delivered**: note + photo (system camera app, stored in app files via `FileProvider`), shown in stop details. Signature not yet |

## Privacy

- **Address search:** when the phone has no geocoder of its own (or it returns nothing), what you type in the search bar (3+ characters, after a 400 ms pause), CSV addresses without coordinates, and long-pressed map points are sent to the Photon server: the public instance at photon.komoot.io unless you set your own in Settings. Requests carry a descriptive User-Agent and a location bias (your last known position or the route's centre), but no account or ID.
- **Map tiles:** fetched from tiles.openfreemap.org (which reveals the map area you view).
- Everything else (route, proof photos, pins) stays on the phone.

## Offline behaviour

Without network the route list, stop editing, delivery/proof, the floating button and on-device Valhalla road times all work. The map falls back to pins on a plain background, and the search bar says it needs network (long-press the map or import a CSV with lat/lng instead).

## Not yet verified on a device

**0.5.0** adds the required route start and has not been run on hardware either. Check: the fresh location fix (accuracy, timeout fallback to last known, location switched off), the gating card, picking the start on the map, the S pin and start row, round trip totals, and that search results near the start come first.

**0.4.0 status:** the 0.3.0 build ran on the user's phone (GrapheneOS-style, no Play services) and showed: no address suggestions, a blank beige map, the overlay bar over the app, a stuck "Undo" strip and a wrapped "Failed" button. 0.4.0 addresses each (see the commit log); **0.4.0 itself has not been run on hardware**.

Blank map, best-supported cause: the beige was MapLibre's default "foreground load color", drawn over the whole MapView (attribution included) until the first frame renders. It never went away. The stop on screen has a full street address, which suggests it was added by long-press, so the map object probably existed but the style never finished loading. MapLibre feeds Android's `getActiveNetworkInfo().isConnected()` into its file source, and when that says "offline" it **waits silently** for connectivity rather than reporting an error. A per-app network block (GrapheneOS Network permission off, or a firewall/VPN app) produces exactly that. One possible route there: 0.2.0 didn't declare `INTERNET`, and GrapheneOS may not switch the Network toggle on automatically when an update adds it (unverified). The same block would also explain why address search got nothing. The style, sprite, glyphs and tiles all load fine from a desktop (checked with curl). 0.4.0 shows a status chip, falls back to pins on a plain background after 10 s, forces MapLibre online when our own HTTP probe succeeds, and has a Network row that tests reachability.

Check first:

- Map status chip text on the phone, with the Network permission on and off; pins and the dashed line on the fallback background; that tiles appear once network is allowed and the chip is tapped.
- Search suggestions from Photon on a phone without Play services; the error texts.
- Floating button: dragging, edge snapping, expand/collapse, Close, the notification's Stop action, hiding while the app is in front, and the permission flow (turn on → Settings → back → starts).
- Swipe gestures leave no leftover strip; snackbars sit above the nav bar/keyboard and disappear on their own.
- Next-stop card buttons on one line at large font sizes.
- Map: pin taps opening stop details, long-press, the pick-a-drop-off-point flow, attribution placement under the search bar.
- Bottom sheet: peek height tracking the header (planning vs. driving card), half/expanded states, list scrolling inside the sheet, edge-to-edge insets (status/nav bars, display cutouts), light/dark and Material You colors.
- Stop details sheet: keyboard over the notes field, time pickers, package-location dialog, photo thumbnail.
- Routes saved by the previous version loading (decode is backward-compatible by design but untested on a real file).
- Carried over: overlay permission flow; `✓` with auto-navigate launching Maps from over another app; the on-device Valhalla engine opening a tile extract (`Routing.kt`); `tools/build-tiles.sh` has been run for Tennessee (see its header), but the phone has not loaded the result yet.

## Permissions

| Permission | Kind | Why |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | install-time | Map tiles, Photon search, optional Valhalla server; connectivity check. (On GrapheneOS the per-app "Network" toggle must also be on.) MapLibre also merges `ACCESS_WIFI_STATE`. |
| `ACCESS_FINE_LOCATION` / `COARSE` | runtime | "Use my current location" as the route start (and search bias). Optional: you can search or pick the start instead. |
| `POST_NOTIFICATIONS` | runtime (13+) | The floating button's foreground-service notification. |
| `SYSTEM_ALERT_WINDOW` | special (Settings screen) | The floating button. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | install-time | Keeps the floating button alive. |
| Camera | none | Proof photos go through the system camera app (`ACTION_IMAGE_CAPTURE` + `FileProvider`). |


## Roadmap

1. Run on a device; fix what breaks (nothing below has run on hardware yet).
2. Build a tile extract for a home region and verify on-device matrix end to end.
3. End at a chosen address (core supports it; no UI yet). A no-key address source better than Photon (being researched).
4. Offline map regions (MapLibre `OfflineManager`, within OpenFreeMap's download limits); address/ETA labels on pins.
5. Barcode + label OCR (ML Kit bundled, CameraX).
6. Signature capture; export proof of delivery.
7. Room if history/queries outgrow SharedPreferences.
8. Server + sync only if a multi-device need appears.

## Library choices

Principle: add a dependency only where it replaces a large amount of work; use services (HTTP) for server-side heavy lifting. Researched 2026-10-31; items marked *unverified* could not be confirmed from sources.

| Roadmap item | Choice | Notes |
|---|---|---|
| Map + offline tiles | **MapLibre Native Android**, `org.maplibre.gl:android-sdk-opengl` 13.7.1 (BSD-2, Maven Central, released 2026-10-15) | The map with numbered pins is central to Circuit's design. We use the OpenGL ES artifact because the default `android-sdk` 13.x declares Vulkan 1.0 as a *required* feature. Brings OkHttp, Timber, coroutines and `fragment` transitively, plus `libmaplibre.so` (APK ~41 MB debug for 2 ABIs). Tiles: **OpenFreeMap** (no key, commercial use OK, attribution required; offline downloads ≤3,000 tiles/50 MB each). `OfflineManager` for regions is not used yet. |
| Lists / bottom sheet | `androidx.recyclerview` 1.1.0, `androidx.coordinatorlayout` 1.1.0 | Already transitive deps of `material`; declared explicitly because the stop list (`ItemTouchHelper` swipe/drag) and `BottomSheetBehavior` use them directly. No new artifacts. |
| Road-network matrix | **Valhalla, on-device** via `io.github.rallista:valhalla-mobile` 0.6.4 (MIT, released 2026-10-06, minSdk 24) | Exposes `sources_to_targets`; we call `matrixRaw` with the same JSON the server API takes, so one tested `ValhallaMatrix` parser serves both. Needs a `valhalla_tiles.tar` for the region (`tools/build-tiles.sh`). ~7 MB native lib per ABI (we ship arm64-v8a + armeabi-v7a). A Valhalla server URL is the fallback; then straight-line estimates. OSRM rejected (no time-dependent speeds). |
| *(earlier note)* | | An earlier search suggested `valhalla-mobile` was stale (0.1.0, Jan 2025); reading its README/releases showed it is current. |
| Solver | **Own solver**; `jsprit` (pure Java) if multi-vehicle is ever needed | No Android port of VROOM/OR-Tools found. VROOM can sit behind the Valhalla server later. |
| Label OCR | **ML Kit text recognition, bundled** + **CameraX** | Bundled = offline, no Play Services (~4 MB/script/ABI). Raise CameraX analysis resolution above 640x480. |
| Package barcodes | **ML Kit barcode scanning, bundled** | More reliable than OCR for tracking numbers. |
| Voice input | **Platform `RecognizerIntent`** | Zero deps; offline availability varies by device. Vosk rejected (40-50 MB/model, weaker). |
| Geocoding | **Platform `android.location.Geocoder`**, then **Photon** over plain `HttpURLConnection` (no new library) | Platform geocoder is absent on phones without Play services. Photon: OSM data, no key, GeoJSON parsed with core `Json`; public instance by default, self-hostable (Settings). Fair use: debounce, ≥3 chars, User-Agent. |
| Local DB (pins, history) | Plain SharedPreferences JSON now; **Room** when queries are needed | |
| HTTP | `HttpURLConnection` + `org.json` | OkHttp only if needed. |
| Avoid | Mapbox proprietary SDK, Vosk, Play Services variants, Fused Location | Keeps the app GrapheneOS-friendly (use platform `LocationManager`). |

Sources: Maven Central metadata for `org.maplibre.gl` (checked 2026-10-31), openfreemap.org + ToS, MapLibre offline API docs, ML Kit text-recognition v2 docs, VROOM and OSRM project pages, valhalla-mobile repo.
