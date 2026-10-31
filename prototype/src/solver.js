/**
 * VRPTW-lite, single vehicle. Stops: { id, lat, lng, serviceSec?, priority?,
 * window?: [startSec, endSec] }, times in seconds from route start.
 *
 * Cost = travel + waiting + large penalties for late arrivals; priority stops
 * are forced ahead of non-priority ones. Construction: nearest feasible
 * neighbour, then 2-opt and or-opt local search on the penalized cost.
 */
const LATE_PENALTY = 10; // cost per second late
const PRIORITY_PENALTY = 1e6; // per non-priority stop placed before a priority one

export function evaluate(order, stops, durations) {
  // `order` is an array of indexes into stops; depot is stops-independent index 0 of the matrix
  let t = 0;
  let prev = 0;
  let cost = 0;
  const etas = [];
  let seenNonPriority = false;
  let priorityViolations = 0;
  for (const idx of order) {
    const s = stops[idx];
    const travel = durations[prev][idx + 1];
    t += travel;
    cost += travel;
    const open = s.window?.[0] ?? 0;
    const close = s.window?.[1] ?? Infinity;
    if (t < open) {
      cost += open - t;
      t = open;
    }
    const late = Math.max(0, t - close);
    cost += late * LATE_PENALTY;
    etas.push({ id: s.id, arrival: t, late });
    t += s.serviceSec ?? 0;
    if (s.priority) {
      if (seenNonPriority) priorityViolations++;
    } else seenNonPriority = true;
    prev = idx + 1;
  }
  cost += priorityViolations * PRIORITY_PENALTY;
  return { cost, etas, totalSec: t };
}

function construct(stops, durations) {
  const remaining = new Set(stops.map((_, i) => i));
  const order = [];
  let prev = 0;
  let t = 0;
  while (remaining.size) {
    const hasPriority = [...remaining].some((i) => stops[i].priority);
    let best = -1;
    let bestScore = Infinity;
    for (const i of remaining) {
      if (hasPriority && !stops[i].priority) continue;
      const s = stops[i];
      const arrive = t + durations[prev][i + 1];
      const open = s.window?.[0] ?? 0;
      const close = s.window?.[1] ?? Infinity;
      const score = Math.max(arrive, open) - t + (arrive > close ? 1e5 : 0) + (Number.isFinite(close) ? (close - arrive) * 1e-3 : 0);
      if (score < bestScore) {
        bestScore = score;
        best = i;
      }
    }
    const s = stops[best];
    t = Math.max(t + durations[prev][best + 1], s.window?.[0] ?? 0) + (s.serviceSec ?? 0);
    prev = best + 1;
    order.push(best);
    remaining.delete(best);
  }
  return order;
}

export function solve(stops, durations, opts = {}) {
  if (stops.length === 0) return { order: [], ...evaluate([], stops, durations) };
  let order = construct(stops, durations);
  let best = evaluate(order, stops, durations, opts).cost;
  let improved = true;
  let rounds = 0;
  while (improved && rounds++ < 100) {
    improved = false;
    // 2-opt
    for (let i = 0; i < order.length - 1; i++)
      for (let j = i + 1; j < order.length; j++) {
        const cand = [...order.slice(0, i), ...order.slice(i, j + 1).reverse(), ...order.slice(j + 1)];
        const c = evaluate(cand, stops, durations, opts).cost;
        if (c < best - 1e-9) ((order = cand), (best = c), (improved = true));
      }
    // or-opt: move one stop elsewhere
    for (let i = 0; i < order.length; i++)
      for (let j = 0; j < order.length; j++) {
        if (i === j) continue;
        const cand = order.slice();
        const [x] = cand.splice(i, 1);
        cand.splice(j, 0, x);
        const c = evaluate(cand, stops, durations, opts).cost;
        if (c < best - 1e-9) ((order = cand), (best = c), (improved = true));
      }
  }
  return { order, ...evaluate(order, stops, durations, opts) };
}

/** Convenience: depot + stops -> ordered stops with optimized_index and ETAs. */
export function optimizeRoute(depot, stops, matrixFn, opts) {
  const { durations, distances } = matrixFn([depot, ...stops]);
  const res = solve(stops, durations, opts);
  const byId = new Map(res.etas.map((e) => [e.id, e]));
  return {
    stops: res.order.map((idx, k) => ({
      ...stops[idx],
      original_index: idx + 1,
      optimized_index: k + 1,
      eta_sec: Math.round(byId.get(stops[idx].id).arrival),
      late_sec: Math.round(byId.get(stops[idx].id).late),
    })),
    totalSec: Math.round(res.totalSec),
    lateStops: res.etas.filter((e) => e.late > 0).length,
    distances,
  };
}
