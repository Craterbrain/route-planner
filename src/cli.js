#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { importManifest } from './manifest.js';
import { haversineMatrix } from './geo.js';
import { optimizeRoute } from './solver.js';
import { navLink } from './deeplink.js';

const [file, depotArg = '', app = 'google'] = process.argv.slice(2);
if (!file) {
  console.error('usage: route-planner <manifest.csv> [depotLat,depotLng] [google|apple|waze]');
  process.exit(2);
}
const { stops, packages, errors } = importManifest(readFileSync(file, 'utf8'), { dayStartSec: 8 * 3600 });
errors.forEach((e) => console.error('warn:', e));
const [dl, dg] = depotArg ? depotArg.split(',').map(Number) : [stops[0]?.lat, stops[0]?.lng];
const res = optimizeRoute({ lat: dl, lng: dg }, stops, haversineMatrix);
const hhmm = (s) => { const t = 8 * 3600 + s; return `${String(Math.floor(t / 3600)).padStart(2, '0')}:${String(Math.floor((t % 3600) / 60)).padStart(2, '0')}`; };
for (const s of res.stops) {
  const pk = packages.filter((p) => p.stop_id === s.id).map((p) => `${p.package_id}@${p.truck_zone ?? '?'}`).join(' ');
  console.log(`${String(s.optimized_index).padStart(2)}. ${hhmm(s.eta_sec)} ${s.priority ? '★' : ' '} ${s.address}  [${pk}]${s.late_sec ? `  LATE ${s.late_sec}s` : ''}`);
  console.log(`      ${navLink(app, s.access)}`);
}
console.log(`\ntotal ${(res.totalSec / 60).toFixed(0)} min, ${res.lateStops} late stop(s)`);
