import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { importManifest, parseCsv } from '../src/manifest.js';
import { haversine, haversineMatrix } from '../src/geo.js';
import { optimizeRoute } from '../src/solver.js';
import { navLink } from '../src/deeplink.js';
import { RouteSession } from '../src/session.js';

const csv = readFileSync(new URL('../examples/manifest.csv', import.meta.url), 'utf8');
const depot = { lat: 39.79, lng: -89.65 };

test('csv handles quotes and CRLF', () => {
  assert.deepEqual(parseCsv('a,b\r\n"x, y","say ""hi"""\r\n'), [['a', 'b'], ['x, y', 'say "hi"']]);
});

test('haversine ~111km per degree latitude', () => {
  assert.ok(Math.abs(haversine({ lat: 0, lng: 0 }, { lat: 1, lng: 0 }) - 111195) < 200);
});

test('manifest merges same address and keeps packages', () => {
  const { stops, packages, errors } = importManifest(csv, { dayStartSec: 8 * 3600 });
  assert.equal(errors.length, 0);
  assert.equal(stops.length, 5);
  assert.equal(packages.length, 6);
  assert.deepEqual(stops[2].window, [3600, 10800]);
});

test('manifest reports rows missing coordinates', () => {
  assert.equal(importManifest('address,lat,lng\nfoo,,\n').errors.length, 1);
});

test('optimizer puts priority first, respects windows, assigns indexes', () => {
  const { stops } = importManifest(csv, { dayStartSec: 8 * 3600 });
  const r = optimizeRoute(depot, stops, haversineMatrix);
  assert.equal(r.stops[0].priority, true);
  assert.equal(r.lateStops, 0);
  assert.deepEqual(r.stops.map((s) => s.optimized_index), [1, 2, 3, 4, 5]);
  assert.ok(r.stops.every((s) => s.original_index >= 1));
});

test('optimizer beats naive manifest order on distance-ish cost', () => {
  const pts = Array.from({ length: 12 }, (_, i) => ({ id: `s${i}`, lat: 40 + ((i * 7) % 12) * 0.01, lng: -90 + ((i * 5) % 12) * 0.01 }));
  const naive = optimizeRoute(depot, pts, haversineMatrix);
  const { durations } = haversineMatrix([depot, ...pts]);
  let t = 0, prev = 0;
  pts.forEach((_, i) => { t += durations[prev][i + 1]; prev = i + 1; });
  assert.ok(naive.totalSec <= t);
});

test('deep links', () => {
  assert.equal(navLink('google', { lat: 1, lng: 2 }), 'google.navigation:q=1,2&mode=d');
  assert.match(navLink('waze', { lat: 1, lng: 2 }), /navigate=yes/);
  assert.throws(() => navLink('x', { lat: 1, lng: 2 }));
});

test('session: deliver advances, prev reverts, auto-navigate returns link', () => {
  const { stops, packages } = importManifest(csv);
  let clock = 0;
  const s = new RouteSession({ stops, packages, autoNavigate: true, now: () => ++clock });
  assert.ok(s.finder().length >= 1);
  const first = s.current.id;
  const link = s.deliver();
  assert.match(link, /^google\.navigation:/);
  assert.notEqual(s.current.id, first);
  s.prev();
  assert.equal(s.current.id, first);
  assert.equal(s.current.status, 'pending');
  assert.equal(s.packages.find((p) => p.stop_id === first).delivery_status, 'pending');
});

test('session: fail requires known reason; injection keeps completed stops', () => {
  const { stops, packages } = importManifest(csv);
  const s = new RouteSession({ stops, packages });
  assert.throws(() => s.fail('nope'));
  s.deliver();
  const doneId = s.stops[0].id;
  s.inject({ id: 'new', address: 'new', lat: 39.8, lng: -89.64, access: { lat: 39.8, lng: -89.64 } }, haversineMatrix, depot);
  assert.equal(s.stops[0].id, doneId);
  assert.equal(s.stops[0].status, 'completed');
  assert.ok(s.stops.some((x) => x.id === 'new'));
  assert.equal(s.current.status, 'pending');
});
