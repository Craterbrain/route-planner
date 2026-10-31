import { randomUUID } from 'node:crypto';

/** Minimal RFC-4180-ish CSV parser (quotes, escaped quotes, CRLF). */
export function parseCsv(text) {
  const rows = [];
  let row = [], field = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"' && text[i + 1] === '"') (field += '"', i++);
      else if (c === '"') q = false;
      else field += c;
    } else if (c === '"') q = true;
    else if (c === ',') (row.push(field), (field = ''));
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field);
      field = '';
      if (row.some((f) => f !== '')) rows.push(row);
      row = [];
    } else field += c;
  }
  row.push(field);
  if (row.some((f) => f !== '')) rows.push(row);
  return rows;
}

const toSec = (hhmm) => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm?.trim() ?? '');
  return m ? +m[1] * 3600 + +m[2] * 60 : null;
};

/**
 * Manifest columns (header row required): address, lat, lng, package,
 * zone, priority, window_start, window_end, service_min, notes.
 * Window times are HH:MM wall clock; pass dayStartSec to make them route-relative.
 * Rows with the same address merge into one stop with several packages.
 */
export function importManifest(csv, { dayStartSec = 0 } = {}) {
  const [header, ...rows] = parseCsv(csv);
  if (!header) return { stops: [], packages: [], errors: ['empty manifest'] };
  const col = Object.fromEntries(header.map((h, i) => [h.trim().toLowerCase(), i]));
  const errors = [];
  const stopsByKey = new Map();
  const packages = [];
  rows.forEach((r, n) => {
    const get = (k) => (col[k] === undefined ? '' : (r[col[k]] ?? '').trim());
    const address = get('address');
    const lat = parseFloat(get('lat'));
    const lng = parseFloat(get('lng'));
    if (!address) return errors.push(`row ${n + 2}: missing address`);
    if (!Number.isFinite(lat) || !Number.isFinite(lng))
      return errors.push(`row ${n + 2}: needs lat/lng (geocode first)`);
    const key = address.toLowerCase();
    let stop = stopsByKey.get(key);
    if (!stop) {
      const ws = toSec(get('window_start'));
      const we = toSec(get('window_end'));
      stop = {
        id: randomUUID(),
        address,
        lat,
        lng,
        // Delivery target = door; access point = where the router should aim.
        target: { lat, lng },
        access: { lat, lng },
        serviceSec: Math.round((parseFloat(get('service_min')) || 2) * 60),
        priority: /^(1|true|yes|y)$/i.test(get('priority')),
        window: ws !== null && we !== null ? [ws - dayStartSec, we - dayStartSec] : undefined,
        notes: get('notes'),
        status: 'pending',
      };
      stopsByKey.set(key, stop);
    } else if (/^(1|true|yes|y)$/i.test(get('priority'))) stop.priority = true;
    packages.push({
      package_id: get('package') || randomUUID(),
      stop_id: stop.id,
      truck_zone: get('zone') || null,
      delivery_status: 'pending',
    });
  });
  return { stops: [...stopsByKey.values()], packages, errors };
}
