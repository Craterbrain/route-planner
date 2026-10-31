import { navLink } from './deeplink.js';
import { optimizeRoute } from './solver.js';

export const FAIL_REASONS = ['recipient_missing', 'access_code_needed', 'wrong_address'];

/**
 * Driver-side route state machine behind the action bar:
 * [Prev] (Delivered) [Next]. Stops are kept in route order; `cursor` is the
 * current stop. Pure state + a log of events; persistence/UI live elsewhere.
 */
export class RouteSession {
  constructor({ stops, packages = [], navApp = 'google', autoNavigate = false, now = () => Date.now() }) {
    this.stops = stops.map((s) => ({ ...s }));
    this.packages = packages.map((p) => ({ ...p }));
    this.navApp = navApp;
    this.autoNavigate = autoNavigate;
    this.now = now;
    this.cursor = Math.max(0, this.stops.findIndex((s) => s.status === 'pending'));
    this.log = [];
  }

  get current() { return this.stops[this.cursor] ?? null; }
  get done() { return this.stops.every((s) => s.status !== 'pending'); }

  /** Packages for the current stop with their truck zones: the "Package Finder" card. */
  finder(stop = this.current) {
    return stop ? this.packages.filter((p) => p.stop_id === stop.id).map((p) => ({ id: p.package_id, zone: p.truck_zone })) : [];
  }

  /** Link for the current stop, aimed at the street access point. */
  link(stop = this.current) {
    return stop ? navLink(this.navApp, stop.access ?? stop) : null;
  }

  #set(stop, status, extra = {}) {
    stop.status = status;
    Object.assign(stop, extra);
    for (const p of this.packages) if (p.stop_id === stop.id) p.delivery_status = status;
    this.log.push({ at: this.now(), stop: stop.id, status, ...extra });
  }

  #advance() {
    const next = this.stops.findIndex((s, i) => i > this.cursor && s.status === 'pending');
    const wrap = next === -1 ? this.stops.findIndex((s) => s.status === 'pending') : next;
    if (wrap !== -1) this.cursor = wrap;
    return this.autoNavigate && !this.done ? this.link() : null;
  }

  /** Returns the deep link to launch if auto-navigate is on. */
  deliver({ note, proof } = {}) {
    if (!this.current) return null;
    this.#set(this.current, 'completed', { completedAt: this.now(), note, proof });
    return this.#advance();
  }

  fail(reason, note) {
    if (!FAIL_REASONS.includes(reason)) throw new Error(`reason must be one of ${FAIL_REASONS}`);
    if (!this.current) return null;
    this.#set(this.current, 'failed', { failReason: reason, note });
    return this.#advance();
  }

  skip() {
    if (!this.current) return null;
    this.#set(this.current, 'skipped');
    return this.#advance();
  }

  next() {
    if (this.cursor < this.stops.length - 1) this.cursor++;
  }

  /** Reverts the previous stop to pending and refocuses on it. */
  prev() {
    if (this.cursor === 0) return;
    this.cursor--;
    const s = this.current;
    if (s.status !== 'pending') this.#set(s, 'pending', { completedAt: undefined, failReason: undefined });
  }

  /** Drop-off pin adjusted by the driver; returned so the caller can persist per-address. */
  adjustAccessPoint(point) {
    this.current.access = { lat: point.lat, lng: point.lng };
    return { address: this.current.address, access: this.current.access };
  }

  /**
   * Live injection: add a stop mid-route and re-optimize only the pending
   * stops from `from` (current position). Completed stops stay untouched.
   */
  inject(newStop, matrixFn, from, opts) {
    const stop = { status: 'pending', serviceSec: 120, ...newStop };
    const finished = this.stops.filter((s) => s.status !== 'pending');
    const pending = [...this.stops.filter((s) => s.status === 'pending'), stop];
    const res = optimizeRoute(from, pending, matrixFn, opts);
    this.stops = [...finished, ...res.stops];
    this.cursor = finished.length;
    return res;
  }
}
