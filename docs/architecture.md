# Architecture review (0.7.8, updated for 0.9.0)

Reviewed 2026-10-31 by reading the code, running the tests and Android lint, and measuring the solver. Nothing here
has been profiled on a phone: the numbers are from a desktop JVM unless stated.

## Shape

```
core/   pure Kotlin/JVM, no Android: model (Stop, Pkg, RouteSession), planning (Solver, Matrix providers,
        Valhalla client), address handling (LabelParser, AddressMatch, offline address search, suggestion chain,
        Google/Photon clients), reports, tile math. ~130 unit tests.
app/    Android: MainActivity (+ ui/ views built in code), RouteActions (planning/adding flows), SessionStore
        (the open route + persistence), Prefs, OfflineMaps, ScanActivity, OverlayService, GoogleTransport (Cronet).
tools/  address-pack and tile builders (Python/shell).
```

What is sound:

- **The dependency direction is right.** `core` knows nothing about Android; every rule that can be tested without a
  device (ordering, ETAs, address IDs, package matching, label parsing, ranking, report CSV, tile counts) lives
  there and is covered. The app is glue and views.
- **One source of truth for the route**: `RouteSession` (immutable lists replaced on change, so a background re-plan
  can read a snapshot) held by `SessionStore`, which notifies observers and saves. Activity and overlay both observe it.
- **Network and slow work are off the main thread**, stale answers are dropped (a token/generation counter), and
  Google requests cancel the previous one.
- **Lifecycle is paired**: observers are added in `onStart` and removed in `onStop`; the MapView forwards every
  lifecycle call; the camera is bound to the scan activity's lifecycle; the address database handle is cached and
  reopened only when the file changes.

## Performance and battery: what was measured and changed

| Item | Before | After |
|---|---|---|
| Solver, 100 / 250 / 400 stops (desktop JVM) | 62 ms / 843 ms / not run | 16 ms / 186 ms / 1.1 s. Same routes (same totals and distances). |
| Solver worst case | unbounded: up to 100 rounds | local search stops after `Solver.SEARCH_BUDGET_MS` (3 s) |
| Why | every 2-opt/or-opt candidate allocated a new list plus an `Eta` per stop | in-place moves on an `IntArray`, objects-free cost, and a candidate is abandoned as soon as its running cost reaches the best so far (every term only adds) |
| Saving the route | synchronous file write on the main thread after every tap | encoded on the caller's thread, written by one background thread; a save overtaken by a newer one is skipped; still write-then-rename |
| Map label bitmaps | a new bitmap per distinct ETA text, never removed (grows with every re-plan) | labels no longer shown are removed from the style |
| Local suggestions (every keystroke) | re-parsed the recents and the remembered-pins JSON each time | parsed once, shared by all `Prefs` instances, replaced on write |
| Release size | n/a | `-Pabis=arm64-v8a` builds 64-bit only: 48 MB instead of 72 MB (debug). `-Pshrink` (R8) saves only ~7 MB and cannot read Kotlin 2.3 metadata with AGP 8.8.2, so it is off by default |

Battery, by inspection: location is one fix with a timeout and cancellation (no updates are left registered); the
only timers are the 350 ms search debounce and the map/location timeouts; the camera analyzer runs at ~3 frames a
second and the camera unbinds with the activity; the map renders on demand; the overlay service only updates a label
when the route changes. Nothing polls.

## Lint

`./gradlew :app:lintDebug` is clean of errors (one real error fixed: an unmarked use of CameraX's experimental
`ImageProxy.image`). Remaining warnings are mostly "newer dependency version available" and `Log` vs Timber. Four
`Recycle` warnings are false positives (the streams and cursors are in `use {}`). `Gravity.LEFT/RIGHT` in the overlay
is deliberate: it positions a button at a physical screen edge.

## Known debts

Done since the first version of this review (0.9.0):

- **Saving is automatic.** `RouteSession` now has an `onChanged` hook (every stop, package, cursor, start/end, name and flag
  setter calls it) and `SessionStore` saves and redraws once per main-thread turn, so a caller that changes the route can't
  forget to `commit()`. Explicit `commit()` calls still work and are harmless.
- **`MainActivity` is 824 lines, down from 976.** Moved out: the offline-maps screens (`OfflineMapsFlow`), report export
  (`ProofExport.share`), New/Save/Open route (`RouteLibraryFlow`), the scan and "add anyway" flows (`ScanFlow`) and Settings
  (`SettingsFlow`). Still inside it: view construction and the start/end flows. The start/end block (~125 lines) is
  tangled with the map-picking banner, the search bar's hint and the sheet state, so it stays until it can be moved with a
  device to test on.
- **Crash records, storage cleanup, a stable signing identity and a launcher icon** are in (see the README for 0.9.0).

Still open:

- **The travel-time matrix is recomputed on every re-plan**, even when only a note changed. Cheap for straight-line
  estimates, a few Valhalla calls on-device. Caching the last matrix by its point list is the fix, held back because
  the degraded-result flag would need to travel with it.
- **`Prefs` stores JSON blobs in SharedPreferences.** Fine at this size; Room only if history/queries are needed.
- **User-visible text is hard-coded English** (no `strings.xml` beyond the app name). Extracting a few hundred strings
  gives nothing until a translation exists, so it is deliberately not done.
- **No UI or instrumentation tests**, and the Android layer (everything outside `core/`) has no automated tests at all.
  That is the biggest risk after "has it run on a phone".
