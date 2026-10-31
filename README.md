# route-planner

A Circuit-style delivery route optimizer, targeting **Android**. Design brief: [docs/idea.md](docs/idea.md).

## Layout

| Path | What |
|---|---|
| `core/` | Pure-Kotlin/JVM engine (no Android deps): manifest import, solver, route state machine, nav deep links. JUnit tests. |
| `app/` | Android app (Kotlin, platform Views + Material 3; deps: `core-ktx`, `material`, `valhalla-mobile`): CSV import, route list, package-finder card, action bar, floating overlay. |
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
(example: `prototype/examples/manifest.csv`).

## Status vs. the idea doc

| Idea point | Status |
|---|---|
| 1. Door target + street access point; geocoding; driver pin adjust | Platform `Geocoder` fills missing coordinates on import; per-address drop-off pins are remembered (`AddressMemory`) and applied on import. **No pin-drag UI yet** (needs the map). |
| 2. Map (MapLibre, offline tiles) | not started |
| 3. VRPTW | single vehicle: priority, windows, service time, ETAs. Road times: on-device Valhalla (import tiles in Settings) → optional server → straight-line fallback; the import dialog says which was used and whether any legs were estimated |
| 4. Packages, truck zones, indexes, CSV import | done; barcode/OCR scanner not started |
| 5. Deep links | Google/Waze launched from the app |
| 6. Action bar + overlay | in-app bar + `SYSTEM_ALERT_WINDOW` overlay (◄ ✓ ✗ ►); fail reasons; auto-navigate; route persists across process death |
| Live injection | **Add stop** (typed or voice via `RecognizerIntent`) geocodes and re-optimizes only the remaining stops |
| Proof of delivery | long-press **Delivered**: note + photo (system camera app, stored in app files via `FileProvider`). Signature not yet |

## Not yet verified on a device

The APK builds but has not been run on a phone or emulator. First things to check: overlay permission flow; `✓` with auto-navigate launching Maps from over another app; the on-device Valhalla engine opening a tile extract (`Routing.kt`); `tools/build-tiles.sh` has been run for Tennessee (see tools/build-tiles.sh header), but the phone has not yet loaded the result.

## Roadmap

1. Run on a device; fix what breaks (nothing below has run on hardware yet).
2. Build a tile extract for a home region and verify on-device matrix end to end.
3. MapLibre map, offline regions, pin-drag (writes `AddressMemory`).
4. Barcode + label OCR (ML Kit bundled, CameraX).
5. Signature capture; export proof of delivery.
6. Room if history/queries outgrow SharedPreferences.
7. Server + sync only if a multi-device need appears.

## Library choices

Principle: add a dependency only where it replaces a large amount of work; use services (HTTP) for server-side heavy lifting. Researched 2026-10-31; items marked *unverified* could not be confirmed from sources.

| Roadmap item | Choice | Notes |
|---|---|---|
| Map + offline tiles | **MapLibre Native Android** (`org.maplibre.gl:android-sdk`) | Open-source; `OfflineManager` for regions. Needs a tile source (OpenFreeMap / Protomaps — *unverified which fits*). Adds native libs. |
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

Sources: MapLibre offline API docs, ML Kit text-recognition v2 docs, VROOM and OSRM project pages, valhalla-mobile repo.
