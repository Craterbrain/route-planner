# route-planner

A Circuit-style delivery route optimizer, targeting **Android**. Design brief: [docs/idea.md](docs/idea.md). UI/UX research on Circuit (now Spoke Route Planner) and the feature checklist: [docs/circuit-research.md](docs/circuit-research.md).

## Layout

| Path | What |
|---|---|
| `core/` | Pure-Kotlin/JVM engine (no Android deps): manifest import, solver (first/last, windows, route clock), route state machine and edits (move, make next, reverse, remove), route summary, nav deep links. JUnit tests. |
| `app/` | Android app (Kotlin, platform Views + Material 3; deps: `core-ktx`, `material`, `valhalla-mobile`, `maplibre`). `MainActivity` (screen wiring), `RouteActions` (import / add / optimize off the UI thread), `SessionStore`, `Prefs`, `Routing`, `OverlayService`; `ui/`: `RouteMap` (MapLibre pins), `RouteSheet` + `StopAdapter` (bottom sheet, next-stop card, swipe/drag list), `StopDetailSheet`, `Dialogs`, `Ui` helpers. |
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

- **Route screen:** full-screen map with numbered stop pins (current stop larger, in the tertiary color; delivered dimmed with ✓; failed in red with !) and a dashed line through the remaining stops in order. A floating search bar at the top adds stops (type an address, or use the mic). Its ⋮ menu has Import spreadsheet, Optimize, Update times, Reverse, Show whole route, Floating action bar, Settings and Clear route. Long-press the map to drop a stop or move the current stop's drop-off pin.
- **Bottom sheet:** empty state (import / add); planning (stop count, time, distance, "done by", late warning, **Optimize** and **Start route**); driving (a next-stop card with ETA, address, first/last and window, notes, **package finder**, **Navigate**, **Failed** / **Proof** / **Delivered**, and ◄ ► to browse without changing anything); finished (summary + new route). Below it is the stop list: swipe left = Delivered (Undo on finished stops), swipe right = Navigate, drag the handle to reorder, tap for details.
- **Stop details:** Navigate / Failed / Delivered (or Undo); proof shown after delivery; Order First/Auto/Last; arrival time (after / before / anytime); time at stop; notes; per-package truck location (Front/Middle/Back · Left/Right · Floor/Shelf + text); "Adjust pin on map" (saved per address); Make next, Move to position, Work on this stop now, Remove.
- **Times:** every route has a clock (`RouteSession.startMs`). Imported HH:MM windows are relative to import time; re-planning mid-route keeps windows meaningful. Edits re-compute ETAs in place; Optimize re-orders the remaining stops from your location.

## Status vs. the idea doc

| Idea point | Status |
|---|---|
| 1. Door target + street access point; geocoding; driver pin adjust | Platform `Geocoder` fills missing coordinates on import and powers the search bar (none on de-Googled phones: then long-press the map or import lat/lng). Drop-off pins: **Adjust pin on map** or long-press → saved in `AddressMemory` and applied on future imports/adds. |
| 2. Map (MapLibre, offline tiles) | MapLibre map with OpenFreeMap tiles (ambient cache only). Offline region download not started. Map can be turned off in Settings. |
| 3. VRPTW | single vehicle: First/Auto/Last, windows (incl. one-sided), service time, ETAs, distances. Road times: on-device Valhalla (import tiles in Settings) → optional server → straight-line fallback |
| 4. Packages, truck zones, indexes, CSV import | done; truck zone editable per package; barcode/OCR scanner not started |
| 5. Deep links | Google/Waze launched from the app, the card, list swipe, or auto-navigate |
| 6. Action bar + overlay | in-app next-stop card + `SYSTEM_ALERT_WINDOW` overlay (◄ ✓ ✗ ►); fail reasons; auto-navigate; route persists across process death |
| Live injection | add stop any time (search, voice, long-press): appended with fresh ETAs, then **Optimize** from the snackbar or menu |
| Proof of delivery | **Proof** button / long-press **Delivered**: note + photo (system camera app, stored in app files via `FileProvider`), shown in stop details. Signature not yet |

## Not yet verified on a device

The APK builds and the core tests pass, but **nothing has been run on a phone or emulator** (none was available). Check first:

- Map: MapLibre (OpenGL ES build) starting, OpenFreeMap positron/dark styles loading, pin taps opening stop details, long-press, the pick-a-drop-off-point flow, attribution placement under the search bar, and the "map couldn't load" notice when offline.
- Bottom sheet: peek height tracking the header (planning vs. driving card), half/expanded states, list scrolling inside the sheet, edge-to-edge insets (status/nav bars, display cutouts), light/dark and Material You colors.
- List gestures: swipe left/right, drag-to-reorder via the handle (and that a re-plan finishing mid-drag doesn't disturb it), Undo snackbars.
- Stop details sheet: keyboard over the notes field, time pickers, package-location dialog, photo thumbnail.
- Routes saved by the previous version loading (decode is backward-compatible by design but untested on a real file).
- Carried over: overlay permission flow; `✓` with auto-navigate launching Maps from over another app; the on-device Valhalla engine opening a tile extract (`Routing.kt`); `tools/build-tiles.sh` has been run for Tennessee (see its header), but the phone has not loaded the result yet.

## Roadmap

1. Run on a device; fix what breaks (nothing below has run on hardware yet).
2. Build a tile extract for a home region and verify on-device matrix end to end.
3. Offline map regions (MapLibre `OfflineManager`, within OpenFreeMap's download limits); address/ETA labels on pins.
4. Barcode + label OCR (ML Kit bundled, CameraX).
5. Signature capture; export proof of delivery.
6. Room if history/queries outgrow SharedPreferences.
7. Server + sync only if a multi-device need appears.

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
| Geocoding | **Platform `android.location.Geocoder`** behind the `Geocoder` interface | Quality/storage terms *unverified*. Fallback: Photon/Pelias on our server. Mapbox permanent geocoding has no free tier. |
| Local DB (pins, history) | Plain SharedPreferences JSON now; **Room** when queries are needed | |
| HTTP | `HttpURLConnection` + `org.json` | OkHttp only if needed. |
| Avoid | Mapbox proprietary SDK, Vosk, Play Services variants, Fused Location | Keeps the app GrapheneOS-friendly (use platform `LocationManager`). |

Sources: Maven Central metadata for `org.maplibre.gl` (checked 2026-10-31), openfreemap.org + ToS, MapLibre offline API docs, ML Kit text-recognition v2 docs, VROOM and OSRM project pages, valhalla-mobile repo.
