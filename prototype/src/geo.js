const R = 6371008.8;
const rad = (d) => (d * Math.PI) / 180;

/** Great-circle distance in meters. */
export function haversine(a, b) {
  const dLat = rad(b.lat - a.lat);
  const dLng = rad(b.lng - a.lng);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/**
 * Offline matrix provider: straight-line distance scaled by a road-detour
 * factor, converted to seconds at an average speed. Swap for an OSRM/VROOM
 * provider with the same shape: (points) => { durations[][], distances[][] }.
 */
export function haversineMatrix(points, { detour = 1.3, speedMps = 11 } = {}) {
  const n = points.length;
  const distances = Array.from({ length: n }, () => new Array(n).fill(0));
  const durations = Array.from({ length: n }, () => new Array(n).fill(0));
  for (let i = 0; i < n; i++)
    for (let j = 0; j < n; j++) {
      if (i === j) continue;
      const d = haversine(points[i], points[j]) * detour;
      distances[i][j] = d;
      durations[i][j] = d / speedMps;
    }
  return { distances, durations };
}
