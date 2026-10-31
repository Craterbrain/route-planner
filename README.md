# route-planner

A Circuit-style delivery route optimizer. Design brief: [docs/idea.md](docs/idea.md).

Zero dependencies (Node ≥ 20). This repo is the **core engine**; apps, map UI and server come later (see roadmap).

## What works now

| Idea point | Module | Status |
|---|---|---|
| 1. Target vs. access point per stop, driver pin adjustment | `manifest.js`, `session.adjustAccessPoint` | model + adjust hook; geocoding not wired |
| 3. VRPTW solver (priority, windows, service time, ETAs) | `solver.js` | single vehicle: nearest-feasible + 2-opt/or-opt |
| 3. Distance matrix | `geo.js` | offline haversine; provider shape matches OSRM/VROOM |
| 4. Packages, truck zones, original/optimized index, CSV import | `manifest.js`, `session.finder` | done |
| 5. Nav deep links (Google/Apple/Waze) | `deeplink.js` | done |
| 6. Action-bar state machine, failure reasons, live stop injection | `session.js` | done |

## Usage

```sh
npm test
node src/cli.js examples/manifest.csv 39.79,-89.65 waze
```

Manifest CSV columns: `address,lat,lng,package,zone,priority,window_start,window_end,service_min,notes`.

## Roadmap

1. Geocoding adapter (Mapbox/Google) filling `target`/`access`; persisted per-address pin overrides.
2. OSRM matrix provider + VROOM/OR-Tools solver behind the same `solve` interface.
3. Server (Postgres/PostGIS) with sync; proof of delivery storage.
4. Mobile app (MapLibre, offline tiles, Android overlay for the action bar).
5. Label OCR and voice address entry.
