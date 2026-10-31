# Circuit (now "Spoke Route Planner") research

Researched 2026-10-31 for the route-planner redesign. Circuit Route Planner was renamed **Spoke Route Planner** on 23 Oct 2025 (getcircuit.com redirects to spoke.com). The Android package is still `com.underwood.route_optimiser`, published by Circuit Routing Limited.

## What I could and could not verify

- **Verified from text sources:** the help-center articles and product-update pages listed under Sources, the App Store listing text, and Spoke's own feature wording. Most of what follows is the vendor's own description, not independent testing.
- **Not verified:** I could not see Play Store or App Store screenshots. The Play listing page didn't render for the fetch tool, and the App Store page had placeholder images with no captions. The product-update pages describe behaviour but almost never layout, colors or exact visual hierarchy. Where the notes below describe **layout** (map above a bottom sheet, a card for the next stop, numbered pins), it is inferred from the help text ("tap a stop on the map to bring up its details sheet", "the sheet has three view sizes", "the X returns to the main stops list", "arrow in the bottom right focuses the list on the current stop", "dynamic stop markers") plus general familiarity with the app. It is **not** confirmed pixel by pixel. Those spots are marked *(inferred)*.

## How Circuit works (driver app), screen by screen

### 1. Route list / create route
- "Create route": name it and pick a date. You can also "Copy stops from a past route".
- Start and end location: tap the start location on the map or in the stop list. "Roundtrip" makes the start and end the same. The current location is used if you set none.

### 2. Main route screen *(layout inferred, behaviour documented)*
- **Map** with **dynamic stop markers**. Markers show whether a stop is a pickup, a priority delivery, delivered or failed, has an error, is new or edited since the last optimization, or is deleted. When you zoom in, the address, ETA and notes appear next to each pin. After an attempt, the ETA changes to the attempt time.
- **Stop list** in a **bottom sheet** under the map. The sheet has **three view sizes** (minimum, medium, maximum), and stop conditions such as time windows show at every size.
- **Search bar** to add stops, with address autocomplete. Next to it are a **microphone** (voice, including reading out a whole list), a **camera/viewfinder** (scan printed addresses or barcodes), and a **⠇ menu** with "Import route manifest", "Scan route manifest", "import spreadsheet" (Android only) and "Skip optimization".
- **Long-press the map** to add a stop there.
- List gestures: **swipe left** on a stop shows Undo / Delivered / Failed, and **swipe right** shows Navigate. **Tap** a stop (in the list or on the map) to open its details sheet.
- After edits, added, edited and removed stops are marked on the map and grouped in separate sections of the list ("preview changes"). You then choose **reoptimize**, which can reorder everything, or **update**, which slots the changes into the current order.

### 3. Optimize / refine
- **"Optimize route"** after adding stops.
- **"Refine"** offers "Reverse route" and "Manually order route". Manual ordering means drawing loops on the map around groups of stops. Ungrouped stops either "fit anywhere" or "come last". Then "Reoptimize route".
- **"Confirm route"** / "Continue editing".
- If some stops "cannot be reached on time", you choose Include or Dismiss. An unreachable address goes to the top of the list as "this stop cannot be accessed".

### 4. Driving: Start route
- **"Start"** highlights the first stop, and the stop details sheet shows it ("clear and uncluttered information about their first stop").
- **"Navigate"** starts in-app navigation (Google-powered) by default. It can hand off to Google Maps, Waze or Apple Maps instead (Settings).
- In the stop sheet: **swipe left/right** for the previous or next stop, mark **Delivered** or **Failed**, and see info and proof of delivery. After Delivered, the sheet **auto-advances** to the next stop. An X returns to the stop list.
- **Stop IDs** (A1, A2… or 1, 2…) are shown on approach so you grab the right package. They stay fixed after the route is confirmed.
- **"Make next"** on any stop of a live route (several at once) is shown with a blue tick, then reoptimize or update.
- Breaks can be added, and ETAs and windows account for them.

### 5. Stop details / edit stop
Fields from the "How to make changes to stops" article, in its order:
- **Order:** First / Auto (default) / Last
- **Type:** Delivery / Pickup (pickups turn purple)
- **Color:** a color tag. Stops with several packages turn teal automatically.
- **Arrival time:** a window, for example "10am and Anytime" means after 10am
- **Time at stop**
- **Packages:** a count with + and −
- **Notes**, plus a **photo** of the package
- **Package finder:** per package ID, a description (Small/Medium/Large × Box/Bag/Letter) and a vehicle location (Front/Middle/Back, Left/Right, Floor/Shelf). This shows as a code such as "SBL".
- **Stop types:** reusable presets
- **Access instructions:** gate codes etc. A star saves them as the default for the address.
- **Change address**, **Adjust the pin location** (for flagged addresses), **Duplicate stop**, **Remove stop**
- **Bulk edit:** select stops by drawing, tapping the map or long-pressing the list, then change type, order, arrival time or time at stop.

### 6. Proof of delivery (team/dispatch variant documented)
- Delivered options: Delivered to recipient / third party / Left in mailbox / Left in safe place / Delivered to pickup point / Other. Then signature, photos, a note for the recipient and an internal note.
- Failed reasons: **Recipient not home, Payment not received, Can't find address, No parking, No time, Other**. Photos and notes can be added.

### 7. Other features
Battery saver mode, Android Auto/CarPlay, real-time traffic alerts, sending and receiving stops between drivers, a shareable live route link, a choice of high-detail or simplified map, the iOS Dynamic Island, and a 10-stop limit on the free plan.

## Feature checklist

Legend: **have** = existed before this work, **built** = added in this redesign, **deferred** = not done (reason given).

| Circuit feature | Status | Notes |
|---|---|---|
| Map with numbered stop pins | **built** | MapLibre + OpenFreeMap. Pins use the same colors as the list badges: pending = primary, current = tertiary and larger, delivered = dimmed with ✓, failed = error with "!". |
| Pins reflect status (dynamic markers) | **built** (partial) | Status colors and marks. No pickup, new or edited markers (no pickup type, no change preview). |
| Address/ETA labels next to pins when zoomed | deferred | Only numbers are drawn. |
| Bottom sheet stop list with sizes | **built** | `BottomSheetBehavior`: collapsed (header), half and expanded states. The list fills the rest. |
| Search bar to add stops | **built** | Platform `Geocoder`, up to 5 matches offered to choose from. Plain search, not live autocomplete. |
| Voice add | **have/built** | `RecognizerIntent` from the mic in the search bar adds the stop directly. Reading out a whole list is deferred. |
| Long-press map to add stop | **built** | Reverse-geocodes when a geocoder exists, otherwise "Dropped pin". Also offers "Set drop-off for current stop". |
| Spreadsheet import | **have** | CSV via the ⋮ menu or the empty state. `priority` now accepts `first`/`last`. |
| Scan labels / manifests / barcodes | deferred | Needs CameraX + ML Kit (bundled). Planned in the README roadmap. |
| Optimize route | **have/built** | The Optimize button re-plans the remaining stops from the current location on the route clock. |
| Update without reoptimizing | **built** | "Update times (keep order)". Edits (drag, move, make next, add) refresh ETAs automatically, keeping the order. |
| "Route changed" preview | **built** (simplified) | A banner with Update / Optimize when first/last, window or time at stop changed. No per-stop added/edited grouping. |
| Reverse route | **built** | Reverses the pending stops only. |
| Manual order | **built** | Drag handle on each row, plus "Move to position…" in stop details. Circuit's draw-loops-on-map method is deferred. |
| Make next | **built** | In stop details while driving. Single stop (Circuit allows several). |
| First / Auto / Last | **built** | `Placement` in core, enforced by the solver as a hard ordering penalty. |
| Arrival time windows | **have/built** | Editable: "Arrive after…", "Arrive before…", or both, via the system time picker. One-sided windows are supported. |
| Time at stop | **have/built** | Editable with − / + per stop. |
| Notes | **have/built** | Editable in stop details, shown on the card and rows. |
| Package finder (location in vehicle) | **have/built** | The card shows package ID · location. The location picker uses Circuit's Front/Middle/Back · Left/Right · Floor/Shelf plus free text. Package size/type description is deferred. |
| Stop IDs (A1, A2) | deferred | We show the route number plus package IDs instead. |
| Start route + highlighted next stop | **built** | "Start route" switches the sheet to a next-stop card: address, ETA, conditions, notes, package finder, Navigate, Failed / Proof / Delivered, and ◄ ► to browse without changing status. |
| Auto-advance after Delivered | **have** | Also optional auto-launch of navigation (Settings). |
| Swipe left = Delivered / Undo, swipe right = Navigate | **built** | On list rows. An Undo snackbar follows every outcome. |
| Navigation hand-off | **have** | Google Maps or Waze deep links aimed at the drop-off point. No in-app turn-by-turn (deferred; Circuit's uses Google's SDK). |
| Failed with reasons | **have/built** | Reasons now follow Circuit's list. "Payment not received" was left out as not relevant; old saved reasons still load. |
| Proof of delivery: photo + note | **have** | "Proof" button / long-press Delivered. The photo is shown in stop details afterwards. |
| Delivered-to options (mailbox, safe place...) | deferred | |
| Signature capture | deferred | Roadmap. |
| Adjust drop-off pin | **built** | Stop details → "Adjust pin on map" → tap the map. Saved via `AddressMemory.remember` and reused for that address on future routes. |
| Edit address / duplicate stop | deferred | Remove + re-add works today. |
| Remove stop | **built** | With confirmation. |
| Route summary (stops, time, distance, finish) | **built** | `RouteSummary` in core: remaining time, road distance from the matrix, "done by" clock time, late-stop count. Pushed back by how far behind plan you are. |
| Floating controls over the nav app | **have** | Overlay action bar (Circuit uses in-app nav / Dynamic Island instead). Still in the ⋮ menu. |
| Settings (nav app, auto-navigate, map, road data, server) | **built** | One dialog. |
| Breaks | deferred | |
| Stop types / colors / pickups | deferred | |
| Bulk edit | deferred | |
| Copy stops between routes / route history | deferred | Single active route only. |
| Recipient notifications, live route sharing | deferred | Needs a server. |
| Multi-driver / send stops to other drivers / dispatch | deferred | Needs a server and accounts. |
| Android Auto, battery saver, traffic alerts | deferred | |
| Offline map regions | deferred | MapLibre's ambient cache keeps viewed tiles. Explicit region download (`OfflineManager`; OpenFreeMap allows ≤3,000 tiles / 50 MB per download, ≤4 parallel requests, unique User-Agent) is next. |

## Map tile source

**OpenFreeMap** public instance (`https://tiles.openfreemap.org/styles/positron` for light, `/styles/dark` for dark). Per openfreemap.org and its ToS (read 2026-10-31): free, no API key, no registration, no cookies, commercial use allowed, no limits on views or requests. Offline downloads are capped at 3,000 tiles or 50 MB each, with at most 4 parallel requests and a unique User-Agent. Provided "AS-IS" with no SLA, and the service may be discontinued. Attribution (OpenFreeMap, OpenMapTiles, OpenStreetMap) is required, so MapLibre's attribution control is kept on. Both styles load glyphs from `tiles.openfreemap.org/fonts`, and our pin labels use "Noto Sans Bold", which that endpoint serves (checked with curl). If the style can't load, the map area shows a notice and the stop list keeps working. A self-hosted Protomaps/OpenFreeMap instance can replace the URL later.

## Sources

- Help: How to create a route: https://help.spoke.com/en/articles/7213916-how-to-create-a-route
- Help: Getting started: https://help.spoke.com/en/articles/1086742-getting-started-with-circuit
- Help: How to make changes to stops: https://routeplannerhelp.spoke.com/en/articles/9619618-how-to-make-changes-to-stops
- Help: Proof of delivery: https://help.spoke.com/en/articles/3850441-how-to-collect-and-manage-proof-of-delivery
- Product updates index: https://spoke.com/route-planner/product-updates
- Enhanced stop details sheet: https://spoke.com/route-planner/product-updates/enhanced-stop-details-sheet-functionality
- Easy to read stop details: https://spoke.com/route-planner/product-updates/easy-to-read-stop-details
- Time-saving stop details sheet design: https://spoke.com/route-planner/product-updates/time-saving-stop-details-sheet-design
- Dynamic stop markers: https://spoke.com/route-planner/product-updates/map-visibility-improved-with-dynamic-stop-markers
- Info on the route map: https://spoke.com/route-planner/product-updates/quickly-see-useful-information-like-stop-type-etas-address-and-notes-on-the-route-map
- Make next on a live route: https://spoke.com/route-planner/product-updates/change-the-order-of-a-live-route-by-making-selected-deliveries-the-next-stop
- Unreachable / skipped stops: https://spoke.com/route-planner/product-updates/simple-management-of-unreachable-or-skipped-stops
- Touch-screen drawing: https://spoke.com/route-planner/product-updates/use-touch-screen-drawing-to-easily-customize-an-optimized-route
- Stop IDs: https://spoke.com/route-planner/product-updates/use-stop-ids-to-quickly-and-easily-find-the-right-package
- App Store listing (text only): https://apps.apple.com/us/app/spoke-route-planner/id1198232244
- Package id `com.underwood.route_optimiser`: APKMirror / BlueStacks listings found via search (Play page itself didn't render for the fetch tool)
- OpenFreeMap: https://openfreemap.org/ and https://openfreemap.org/tos/
- MapLibre Android on Maven Central: https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk-opengl/
