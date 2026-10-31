#!/usr/bin/env bash
# Build valhalla_tiles.tar (on-device routing data) from an OSM extract.
#   tools/build-tiles.sh illinois-latest.osm.pbf [out-dir]
# Extracts: https://download.geofabrik.de/  (a state is typically a few hundred MB of tiles).
# Then get it onto the phone (e.g. `avs send out/valhalla_tiles.tar`) and use
# Settings -> Import map data in the app.
# Verified 2026-10-31 with Podman: Tennessee (191 MB pbf) -> 412 MB tar in ~40 s, Valhalla 3.9.1
# (same engine version as valhalla-mobile 0.6.4). Uses podman if present, else docker
# (override with CONTAINER_RT). Default matrix limit is 400 km between points.
set -euo pipefail
pbf=$(realpath "${1:?usage: build-tiles.sh region.osm.pbf [out-dir]}")
out=$(mkdir -p "${2:-out}" && realpath "${2:-out}")
img=${VALHALLA_IMAGE:-ghcr.io/valhalla/valhalla:latest}
rt=${CONTAINER_RT:-$(command -v podman || command -v docker)}

cp "$pbf" "$out/region.osm.pbf"
"$rt" run --rm -v "$out:/data" "$img" bash -c '
  set -e
  valhalla_build_config \
    --mjolnir-tile-dir /data/tiles \
    --mjolnir-tile-extract /data/valhalla_tiles.tar \
    --mjolnir-traffic-extract /data/traffic.tar > /data/valhalla.json
  valhalla_build_tiles -c /data/valhalla.json /data/region.osm.pbf
  valhalla_build_extract -c /data/valhalla.json -v
'
ls -lh "$out/valhalla_tiles.tar"
