# route-planner

A Circuit-style delivery route optimizer, targeting **Android**. Design brief: [docs/idea.md](docs/idea.md). UI/UX research on Circuit (now Spoke Route Planner) and the feature checklist: [docs/circuit-research.md](docs/circuit-research.md).

## Layout

| Path | What |
|---|---|
| `core/` | Pure-Kotlin/JVM engine (no Android deps): manifest import, solver (first/last, windows, route clock), route state machine and edits (move, make next, reverse, remove), route summary, suggestion chain (`Suggest.kt`: providers, ranking, merger, circuit breaker), Google suggest parser (`google/`), offline address pack normalizer + search (`address/`), Photon geocoder, nav deep links. JUnit tests. |
| `app/` | Android app (Kotlin, platform Views + Material 3; deps: `core-ktx`, `material`, `valhalla-mobile`, `maplibre`). `MainActivity` (screen wiring), `RouteActions` (import / add / optimize off the UI thread), `AddressSearch` (provider chain), `AddressPack` (offline address data over read-only SQLite), `GoogleTransport` (Play services Cronet or plain HTTP), `NetStatus` (reachability), `SessionStore`, `Prefs`, `Routing`, `OverlayService` (floating button); `ui/`: `RouteMap` (MapLibre pins + diagnostics), `SearchPanel` (suggestions), `RouteSheet` + `StopAdapter` (bottom sheet, next-stop card, swipe/drag list), `StopDetailSheet`, `PermissionsSheet`, `Dialogs`, `Ui` helpers. |
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
- **Search bar:** rows appear as you type. First your own (instant, no network): recent places, this route's stops and saved drop-off pins, matched on word prefixes. Then, after a 350 ms pause and 3+ characters, the first source in this chain that answers: **Google suggestions** (unofficial, see below; skipped while its circuit breaker is open) → **offline address pack** → **the phone's geocoder** → **Photon (OSM)**. A caption names the source ("Suggestions: Google"). Every row has a fill-in arrow that copies its text into the box without picking it. A typed house number ranks results whose street words match (minus a point for a different direction or street type), then nearest to the start. Digits-only input keeps only results within 80 km of the start. Rows with an estimated position (interpolated, street-level) say so, and the stop shows "Estimated position, adjust pin". Empty or failed lookups say so ("No matches", "Couldn't reach address search", "No network access…"). The mic fills the bar by voice. The ⋮ menu has Import spreadsheet, Optimize, Update times, Reverse, Show whole route, Floating button (on/off), Permissions & network, Settings and Clear route. Long-press the map to drop a stop, set the start, or move the current stop's drop-off pin.
- **Full addresses** (spreadsheet rows without lat/lng): offline pack exact or interpolated hit → the phone's geocoder (on phones with Play services this is Google's geocoder, no key needed) → Google's top suggestion → Photon → the pack's estimated position. Rows that only got an estimated position are listed after import and flagged on the stop. A remembered drop-off pin always wins over any looked-up position.
- **Floating button (overlay):** a round button over Google Maps / Waze; drag it (it snaps to the nearest edge, position remembered), tap to expand: current stop, ◄ (previous stop, set back to pending) ✓ Delivered ✗ Failed ► Next, Open app, Close. It collapses after Delivered/Failed/Open app, hides while Route Planner itself is on screen, and its notification has a Stop action.
- **Permissions & network sheet:** shown on first launch and from ⋮ or Settings. One row each for Network (actually tests the map and geocoder hosts; explains GrapheneOS's per-app Network toggle with a button to App info), Location, Notifications (Android 13+), Display over other apps (opens the system screen; the floating button starts only once it's granted), and optional battery-optimization exemption. Re-checked every time the app resumes; nothing blocks the app.
- **Bottom sheet:** empty state (import / add); planning (stop count, time, distance, "done by", late warning, **Optimize** and **Start route**); driving (a next-stop card with ETA, address, first/last and window, notes, **package finder**, **Navigate**, **Failed** / **Proof** / **Delivered**, and ◄ ► to browse without changing anything); finished (summary + new route). Below it is the stop list: swipe left = Delivered (Undo on finished stops), swipe right = Navigate, drag the handle to reorder, tap for details.
- **Stop details:** Navigate / Failed / Delivered (or Undo); proof shown after delivery; Order First/Auto/Last; arrival time (after / before / anytime); time at stop; notes; per-package truck location (Front/Middle/Back · Left/Right · Floor/Shelf + text); "Adjust pin on map" (saved per address); Make next, Move to position, Work on this stop now, Remove.
- **Times:** every route has a clock (`RouteSession.startMs`). Imported HH:MM windows are relative to import time; re-planning mid-route keeps windows meaningful. Edits re-compute ETAs in place; Optimize re-orders the remaining stops from your location.

## Status vs. the idea doc

| Idea point | Status |
|---|---|
| 1. Door target + street access point; geocoding; driver pin adjust | Geocoding chain platform `Geocoder` → Photon for the search bar, long-press reverse lookup and CSV import. Drop-off pins: **Adjust pin on map** or long-press → saved in `AddressMemory` and applied on future imports/adds. |
| 2. Map (MapLibre, offline tiles) | MapLibre map with OpenFreeMap tiles. **Offline maps** (Settings → Offline maps, or ⋮): downloads the area around your route (start, stops, end, padded) for both the light and dark style, capped at 3,000 tiles per download (OpenFreeMap's limit) by lowering the deepest zoom for big areas; saved maps listed with size and delete. Map can be turned off in Settings. |
| 3. VRPTW | single vehicle: First/Auto/Last, windows (incl. one-sided), service time, ETAs, distances. Road times: on-device Valhalla (import tiles in Settings) → optional server → straight-line fallback |
| 4. Packages, truck zones, indexes, CSV import | done; truck zone editable per package. **Scan label** (camera button in the search bar, or ⋮): CameraX + ML Kit text recognition (bundled, offline) reads the label's text; **barcodes and tracking numbers are ignored**. The delivery address is offered first (the bigger print, or the one after "SHIP TO"), a return address second (cue words FROM / RETURN / SENDER, or clearly smaller print). Tapping an address checks it with Google first (the suggestion service, then the phone's Google-backed geocoder, then the offline pack's exact hit); if a result has the same house number, street and ZIP (`AddressMatch`), the stop is **added immediately**; otherwise the text goes into the search bar to pick or edit. **Find package** (⋮, when the route has packages, e.g. from a CSV): scan a barcode to jump to its stop and see its truck location. |
| 5. Deep links | Google/Waze launched from the app, the card, list swipe, or auto-navigate |
| 6. Action bar + overlay | in-app next-stop card + `SYSTEM_ALERT_WINDOW` overlay (◄ ✓ ✗ ►); fail reasons; auto-navigate; route persists across process death |
| Live injection | add stop any time (search, voice, long-press): appended with fresh ETAs, then **Optimize** from the snackbar or menu |
| Proof of delivery | **Proof** button / long-press **Delivered**: note, photo (system camera app) and a finger-drawn **recipient signature** (PNG, black on white), stored in app files and shown in stop details. **Export delivery report** (⋮) shares a zip: `report.csv` (stop, address, status, time, failure reason, packages, note, photo, signature) plus `photos/` and `signatures/`. |
| Route end | Starting point row → "ROUTE ENDS": at the last stop, back at the start (round trip), or **at an address** (search, pick on the map, or long-press → Set as route end). An "E" pin marks it on the map; the drive there counts toward the finish time and distance. |

## Google suggestions (unofficial)

The user chose this knowingly for a small private distribution.

- **What it is:** while you type, the app sends the same request the Google Maps website sends from its search box (`www.google.com/s?tbm=map&suggest=p…`), with no account and no API key. It is not an official API and using it this way is against Google's terms. It can change or stop working at any time.
- **What is sent:** the typed text (3+ characters, after a 350 ms pause), a location bias (the route start), and, as with any request, your IP address. Headers are those of desktop Chrome on Windows. The claimed Chrome version is the version of the Chromium network stack (Cronet) from Google Play services that carries the request, so the headers and the connection agree. Without Play services the request goes over plain HTTP and claims Chrome `GoogleWeb.FALLBACK_CHROME_MAJOR` (141). No cookies are needed: verified on 2026-10-31 with a handful of requests from a US connection, with no consent redirect. No per-install fingerprints, no timing jitter and no remote configuration. Every request constant is in one file, `core/.../google/GoogleSuggest.kt` (`GoogleWeb`), so a format change is a one-file update. The typed text is never logged.
- **When it fails:** three failures in a row (network errors or a reply the parser can't read) open a circuit breaker for 10 minutes. The app then uses the offline pack, the phone's geocoder and Photon, and the suggestion list says "Google suggestions unavailable, using offline/OSM".
- **Switch it off:** Settings → Address search → "Google suggestions (unofficial)". The other sources keep working.

## Offline address data

An imported address pack (`addresses.sqlite`, one US state, built by `tools/build-addresses.py`, format in [docs/address-pack.md](docs/address-pack.md)) answers typed addresses on the phone with no network. Import it in Settings → Address data. The file is copied to app storage, checked (`PRAGMA user_version = 1`, `meta.format = 1`) and opened read-only. Settings shows its state, address count, build date, release and the attribution from its `meta` table.

- The search is a line-for-line Kotlin port of `tools/query-address.py`: word tries, a half-typed last word, comma text as city/ZIP filter, then exact → interpolated → nearest mapped house → street centroid, never interpolating across different city/ZIP groups, ranked by locality match, distance band from the start, match type, direction and word fit. The normalizer passes all 124 shared vectors in `tools/address-normalize-cases.json` (the core test reads that file directly). The Kotlin search returns the same answers as the Python reference: the committed fixture replays 11 public queries, and 5 more were checked on the build machine from a local fixture.
- Ranking (same in Kotlin and the Python reference): a typed city/ZIP that contradicts a result puts it last; then match type (exact house > interpolated > nearest mapped house > street centroid), so an exact house never loses to a nearby estimate; then within 25 km of the start before farther; then street-word fit; then distance.
- **License (unverified):** Overture lists the National Address Database under the "National Address Database Access and Usage License". Every row says `LicenseRef-Public-Domain-US`. The license text could not be fetched (transportation.gov blocked the request), so it has not been read. Read it before distributing a pack.

## Privacy

- **Address search:** with Google suggestions on, typed text goes to Google (see above). The phone's geocoder (Google's on phones with Play services) gets full addresses, and long-pressed points for reverse lookup. Photon (photon.komoot.io unless you set your own) gets text only when the sources before it had nothing. The offline pack and the local rows send nothing. Recent places are stored on the phone (up to 50; remove them in Settings → Recent searches).
- **Map tiles:** fetched from tiles.openfreemap.org (which reveals the map area you view).
- Everything else (route, proof photos, pins) stays on the phone.

## Offline behaviour

Without network the route list, stop editing, delivery/proof, the floating button and on-device Valhalla road times all work. The map falls back to pins on a plain background. With an imported address pack, typed addresses and spreadsheet rows still resolve offline; without one the search bar says it needs network (long-press the map or import a CSV with lat/lng instead).

## Not yet verified on a device

**0.7.3**: label ranking reworked against a photo of a real USPS Parcel Select label (the test replays that label's lines): the return block there is printed at about 70% the size of the recipient's, so print size (measured over all of an address's lines) decides first, with similar sizes going to the lower address; endorsement lines such as "ADDRESS SERVICE REQUESTED" / "RETURN SERVICE REQUESTED" are not cues; ZIP+4 written with a hyphen, en dash or em dash is read and trimmed to the ZIP; the name line above the street ("JAMES VIDUSKI") is saved in the stop's notes as "For James Viduski". Not yet run through ML Kit on a real photo: the unknown is what heights and lines ML Kit actually reports.

**0.7.2**: label scanning ignores barcodes; ranks the delivery address first and the return address second; tapping an address validates it with Google and adds it directly (search bar only if it doesn't validate). Check on a real label: that the bigger address is the primary one, cue words (SHIP TO / FROM), and that a confirmed address is added with no extra tap.

**0.7.1** (from the first look at 0.7.0 on the phone, which showed the map rendering): a camera button next to the mic in the search bar for Scan label, the empty-state actions above the explanatory text, and a dark-map contrast boost. OpenFreeMap's "dark" style draws roads at #181818 on a #0c0c0c background and labels at 37-40% grey; `RouteMap.boostDarkContrast` lifts road, building, water, park and label colors at runtime (by layer id, skipped if an id is missing). The exact values were picked from the style's own numbers, not tuned on a screen, so expect to adjust them. The light style is untouched.

**0.7.0** adds signature capture, the delivery-report export, a chosen route end, offline map download, and label/barcode scanning. None has run on hardware. Check: the signature pad (drawing, Clear, Save, the saved PNG in stop details), the export sheet opening with a zip that contains the CSV and files, the end-address flows and the E pin, the offline download dialog (tile estimate, progress, Cancel, resulting size, delete; then the map loading in airplane mode in both light and dark), the camera permission prompt, label reading on a real shipping label (addresses listed, tracking number captured, the add-stop flow), Find package scanning, and the larger APK (ML Kit's bundled OCR and barcode models; dropping the 32-bit ABI would save about a third).

**0.6.0** adds Google suggestions over Play services' Cronet, local suggestions with fill-in arrows, the offline address pack, and approximate-position flags. None of it has run on hardware. Check: suggestions appearing as you type (and which source the caption names), that Settings shows "Chromium network stack N (Google Play services)" rather than plain HTTP, the breaker message when Google fails, importing `addresses.sqlite` (125 MB copy) and offline lookups with Wi-Fi off, and the estimated-position flags.

**0.5.0** adds the required route start and has not been run on hardware either. Check: the fresh location fix (accuracy, timeout fallback to last known, location switched off), the gating card, picking the start on the map, the S pin and start row, round trip totals, and that search results near the start come first.

**0.4.0 status:** the 0.3.0 build ran on the user's phone (which, it turned out, does have Play services) and showed: no address suggestions, a blank beige map, the overlay bar over the app, a stuck "Undo" strip and a wrapped "Failed" button. 0.4.0 addresses each (see the commit log); **0.4.0 itself has not been run on hardware**.

Blank map, best-supported cause: the beige was MapLibre's default "foreground load color", drawn over the whole MapView (attribution included) until the first frame renders. It never went away. The stop on screen has a full street address, which suggests it was added by long-press, so the map object probably existed but the style never finished loading. MapLibre feeds Android's `getActiveNetworkInfo().isConnected()` into its file source, and when that says "offline" it **waits silently** for connectivity rather than reporting an error. A per-app network block (GrapheneOS Network permission off, or a firewall/VPN app) produces exactly that. One possible route there: 0.2.0 didn't declare `INTERNET`, and GrapheneOS may not switch the Network toggle on automatically when an update adds it (unverified). The same block would also explain why address search got nothing. The style, sprite, glyphs and tiles all load fine from a desktop (checked with curl). 0.4.0 shows a status chip, falls back to pins on a plain background after 10 s, forces MapLibre online when our own HTTP probe succeeds, and has a Network row that tests reachability.

Check first:

- Map status chip text on the phone, with the Network permission on and off; pins and the dashed line on the fallback background; that tiles appear once network is allowed and the chip is tapped.
- Search suggestions and their error texts.
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
| Camera | runtime, only when scanning | **Scan label / Find package** ask for it when the scan screen opens. Proof photos still go through the system camera app (`ACTION_IMAGE_CAPTURE` + `FileProvider`), which needs none. |


## Roadmap

1. Run on a device; fix what breaks (nothing since 0.3.0 has run on hardware).
2. Build a tile extract for a home region and verify on-device matrix end to end (done for Tennessee; the phone hasn't loaded it).
3. Street-centerline snapping for street-level address hits; address packs for more states.
4. (done) ETA labels on map pins: arrival time beside each pending pin (street too for the current stop), zoom 12+.
5. Room if history/queries outgrow SharedPreferences.
6. Server + sync only if a multi-device need appears.

Done since the first roadmap: Circuit-style UI, start/end location, Google/offline/local address suggestions, offline address packs, offline map regions, label and barcode scanning, signature capture, delivery report export.

## Library choices

Principle: add a dependency only where it replaces a large amount of work; use services (HTTP) for server-side heavy lifting. Researched 2026-10-31; items marked *unverified* could not be confirmed from sources.

| Roadmap item | Choice | Notes |
|---|---|---|
| Map + offline tiles | **MapLibre Native Android**, `org.maplibre.gl:android-sdk-opengl` 13.7.1 (BSD-2, Maven Central, released 2026-10-15) | The map with numbered pins is central to Circuit's design. We use the OpenGL ES artifact because the default `android-sdk` 13.x declares Vulkan 1.0 as a *required* feature. Brings OkHttp, Timber, coroutines and `fragment` transitively, plus `libmaplibre.so` (APK ~41 MB debug for 2 ABIs). Tiles: **OpenFreeMap** (no key, commercial use OK, attribution required; offline downloads ≤3,000 tiles/50 MB each). `OfflineManager` for regions is not used yet. |
| Lists / bottom sheet | `androidx.recyclerview` 1.1.0, `androidx.coordinatorlayout` 1.1.0 | Already transitive deps of `material`; declared explicitly because the stop list (`ItemTouchHelper` swipe/drag) and `BottomSheetBehavior` use them directly. No new artifacts. |
| Road-network matrix | **Valhalla, on-device** via `io.github.rallista:valhalla-mobile` 0.6.4 (MIT, released 2026-10-06, minSdk 24) | Exposes `sources_to_targets`; we call `matrixRaw` with the same JSON the server API takes, so one tested `ValhallaMatrix` parser serves both. Needs a `valhalla_tiles.tar` for the region (`tools/build-tiles.sh`). ~7 MB native lib per ABI (we ship arm64-v8a + armeabi-v7a). A Valhalla server URL is the fallback; then straight-line estimates. OSRM rejected (no time-dependent speeds). |
| *(earlier note)* | | An earlier search suggested `valhalla-mobile` was stale (0.1.0, Jan 2025); reading its README/releases showed it is current. |
| Solver | **Own solver**; `jsprit` (pure Java) if multi-vehicle is ever needed | No Android port of VROOM/OR-Tools found. VROOM can sit behind the Valhalla server later. |
| Label OCR | **ML Kit text recognition, bundled** (`com.google.mlkit:text-recognition` 16.0.1) + **CameraX** 1.4.2 (`camera-core/camera2/lifecycle/view`) | Bundled = offline, no Play Services. Measured: the OCR pipeline is ~10 MB (arm64) / 6.5 MB (arm32) of native code and barcode ~4.7 / 3.1 MB, so the debug APK went from 42 to 72 MB. CameraX 1.6 needs AGP 8.9.1+, so 1.4.2 is used with AGP 8.8.2. Analysis runs at 1920x1080, not the 640x480 default. |
| Package barcodes | **ML Kit barcode scanning, bundled** (`com.google.mlkit:barcode-scanning` 17.3.0) | Used only by **Find package** (match a scanned barcode to a package already on the route); label scanning ignores barcodes. If Find package is dropped, this dependency (~8 MB of native code per ABI pair) can go. |
| Voice input | **Platform `RecognizerIntent`** | Zero deps; offline availability varies by device. Vosk rejected (40-50 MB/model, weaker). |
| Geocoding | Google suggest (unofficial, keyless) → offline pack (own SQLite, no library) → **platform `android.location.Geocoder`** → **Photon** over plain `HttpURLConnection` | The platform geocoder is Google-backed on phones with Play services and absent without them. Photon: OSM data, no key, GeoJSON parsed with core `Json`; public instance by default, self-hostable (Settings). Fair use: debounce, ≥3 chars, User-Agent. |
| Google connection | **`com.google.android.gms:play-services-cronet` 18.1.1** (Google Maven) | Chromium's network stack from Google Play services, used only for the Google suggestion request. It needs Play services on the phone; without them the request falls back to plain HTTP. Adds about 0.9 MB to the APK (play-services-base, basement, tasks and cronet-api; no native libraries, since Cronet itself lives in Play services). Play services keeps its Chrome version current. The embedded `org.chromium.net:cronet-embedded` (Maven stops at 143, ~6 MB of native code per ABI) is **not** shipped. |
| Offline addresses | Own pack format, read with platform `SQLiteDatabase` | No FTS5/JSON1 needed. 125 MB for Tennessee. |
| Local DB (pins, history) | Plain SharedPreferences JSON now; **Room** when queries are needed | |
| HTTP | `HttpURLConnection` + `org.json` | OkHttp only if needed. |
| Avoid | Mapbox proprietary SDK, Vosk, Play Services variants, Fused Location | Keeps the app GrapheneOS-friendly (use platform `LocationManager`). |

Sources: Maven Central metadata for `org.maplibre.gl` (checked 2026-10-31), openfreemap.org + ToS, MapLibre offline API docs, ML Kit text-recognition v2 docs, VROOM and OSRM project pages, valhalla-mobile repo.
