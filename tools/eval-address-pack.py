#!/usr/bin/env python3
"""Match-rate evaluation for an address pack.

    tools/eval-address-pack.py data/tn/addresses.sqlite data/tn/overture-TN-2026-10-21.0.parquet \
        [--n 300] [--seed 1] [--show-failures 40]

Samples N real addresses from the cached Overture extract (needs duckdb),
rewrites each the way people type (lowercase, street type abbreviated or
expanded, directionals abbreviated or dropped, route names as US-70 / SR 70 /
US Hwy 70 ...), and runs tools/query-address.py's search() in four modes:

  city  - "<number> <street> <city>"        (city or postal city, random)
  zip   - "<number> <street>, <zip>"
  near  - "<number> <street>" + --near a point up to 5 km from the address
  bare  - "<number> <street>" with nothing else (statewide ambiguity)

If the source row has a unit, it is typed ("apt 4" / "#4" / "unit 4") half
the time.

Success = top result within 10 m of the source point. Prints rates, match
types and failure categories.
"""

import argparse
import collections
import importlib.util
import math
import os
import random
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_spec = importlib.util.spec_from_file_location("query_address", os.path.join(HERE, "query-address.py"))
qa = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(qa)
from addrnorm import normalize_street  # noqa: E402

TYPE_VARIANTS = {
    "street": ["Street", "St", "St."], "avenue": ["Avenue", "Ave", "Av"],
    "road": ["Road", "Rd", "Rd."], "drive": ["Drive", "Dr"], "lane": ["Lane", "Ln"],
    "boulevard": ["Boulevard", "Blvd"], "parkway": ["Parkway", "Pkwy"],
    "court": ["Court", "Ct"], "circle": ["Circle", "Cir"], "place": ["Place", "Pl"],
    "trail": ["Trail", "Trl"], "terrace": ["Terrace", "Ter"], "highway": ["Highway", "Hwy"],
    "pike": ["Pike"], "cove": ["Cove", "Cv"], "way": ["Way"], "loop": ["Loop"],
}
DIR_VARIANTS = {"north": "N", "south": "S", "east": "E", "west": "W",
                "northeast": "NE", "northwest": "NW", "southeast": "SE", "southwest": "SW"}
ROUTE_FORMS = ["US-{n}", "US {n}", "US Hwy {n}", "US Highway {n}", "Hwy {n}", "Highway {n}",
               "SR-{n}", "State Route {n}", "TN-{n}", "TN {n}"]


def perturb(street, rnd):
    words = street.split()
    out = []
    i = 0
    while i < len(words):
        w = words[i]
        lw = w.lower()
        if lw in ("highway", "hwy") and i + 1 < len(words) and words[i + 1].isdigit():
            out.append(rnd.choice(ROUTE_FORMS).format(n=words[i + 1]))
            i += 2
            continue
        if lw in DIR_VARIANTS:
            r = rnd.random()
            if r < 0.3:
                i += 1
                continue  # dropped
            out.append(DIR_VARIANTS[lw] if r < 0.75 else w)
        elif lw in TYPE_VARIANTS:
            out.append(rnd.choice(TYPE_VARIANTS[lw]))
        else:
            out.append(w)
        i += 1
    s = " ".join(out)
    return s.lower() if rnd.random() < 0.6 else s


def offset(lat, lon, rnd, max_m=5000):
    d = rnd.uniform(0, max_m)
    b = rnd.uniform(0, 2 * math.pi)
    return (lat + d * math.cos(b) / 111320.0,
            lon + d * math.sin(b) / (111320.0 * math.cos(math.radians(lat))))


def classify(res, truth, db, duck, parquet):
    """Failure category for a miss."""
    if not res:
        return "no street matched"
    top = res[0]
    d = qa.haversine_m(top["lat"], top["lon"], truth["lat"], truth["lon"])
    if top["type"] == qa.EXACT and top["norm"] == normalize_street(truth["street"]):
        # same street/number/unit/zip at several points >10 m apart in NAD?
        pts = duck.execute(f"""SELECT y, x FROM read_parquet('{parquet}')
            WHERE street = ? AND number = ? AND coalesce(unit,'') = ?
              AND coalesce(postcode,'') = ?""",
            [truth["street"], truth["number"], truth["unit"], truth["zip"] or ""]).fetchall()
        if any(qa.haversine_m(y, x, truth["lat"], truth["lon"]) > 10 for y, x in pts):
            return "ambiguous in source: same number+unit+zip at several points (multi-building complex)"
    if top["type"] == qa.EXACT and top["norm"] == normalize_street(truth["street"]) \
            and truth["unit"] and not truth["unit_typed"]:
        return ("multi-unit address, unit not typed: building/centroid point returned "
                + ("<=50 m" if d <= 50 else ">50 m") + " away")
    want = normalize_street(truth["street"])
    if top["norm"] != want:
        if top["norm"].split()[:-1] == want.split()[:-1] or top["norm"].replace(" ", "") == want.replace(" ", ""):
            return "other directional/variant of same street ranked first"
        return f"different street ranked first"
    if top["type"] != qa.EXACT:
        return f"right street name, top hit {top['type']} (other city/zip group)"
    return "right street + number, but another group (same name, other town/zip)"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack")
    ap.add_argument("parquet")
    ap.add_argument("--n", type=int, default=300)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--show-failures", type=int, default=25)
    a = ap.parse_args()
    import duckdb
    duck = duckdb.connect()
    rows = duck.execute(f"""
        SELECT number, street, coalesce(unit,''), levels[2], postal_city, postcode, y, x
        FROM read_parquet('{a.parquet}') USING SAMPLE {a.n} ROWS (reservoir, {a.seed})""").fetchall()
    rnd = random.Random(a.seed)
    db = sqlite3.connect(f"file:{a.pack}?mode=ro", uri=True)
    modes = ["city", "zip", "near", "bare"]
    ok = collections.Counter()
    addr_ok = collections.Counter()
    interp_err = collections.defaultdict(list)
    interp_done = set()
    types = {m: collections.Counter() for m in modes}
    fails = {m: collections.Counter() for m in modes}
    examples = []
    import time
    lat_ms = []
    for number, street, unit, city, pcity, zipc, y, x in rows:
        typed = perturb(street, rnd)
        unit_typed = bool(unit) and rnd.random() < 0.5
        truth = {"street": street, "lat": y, "lon": x, "unit": unit, "unit_typed": unit_typed,
                 "number": number, "zip": zipc}
        if unit_typed:
            typed += rnd.choice([" apt ", " #", " unit "]) + unit
        for m in modes:
            near = None
            if m == "city":
                c = rnd.choice([v for v in (city, pcity) if v] or [""])
                text = f"{number} {typed} {c.lower() if rnd.random() < 0.6 else c}"
            elif m == "zip":
                text = f"{number} {typed}, {zipc or ''}"
            elif m == "near":
                text = f"{number} {typed}"
                near = offset(y, x, rnd)
            else:
                text = f"{number} {typed}"
            t = time.perf_counter()
            res = qa.search(db, text, near, 5)
            lat_ms.append((time.perf_counter() - t) * 1000)
            hit = res and qa.haversine_m(res[0]["lat"], res[0]["lon"], y, x) <= 10
            if res and res[0]["type"] == qa.EXACT and res[0]["norm"] == normalize_street(street) \
                    and qa.haversine_m(res[0]["lat"], res[0]["lon"], y, x) <= 500:
                addr_ok[m] += 1
                if m == "city" and res[0]["street_id"] not in interp_done:
                    interp_done.add(res[0]["street_id"])
                    # leave-one-out: pretend this number is missing and
                    # interpolate it from its neighbours on the same street
                    house = qa.split_house_number(number, "")
                    est = qa.lookup_number(db, res[0]["street_id"],
                                           (house[0] + "#", house[1], ""))
                    if est:
                        interp_err[est[0]].append(qa.haversine_m(est[1], est[2], y, x))
            if hit:
                ok[m] += 1
                types[m][res[0]["type"]] += 1
            else:
                cat = classify(res, truth, db, duck, a.parquet)
                fails[m][cat] += 1
                if len(examples) < a.show_failures and m in ("city", "near") \
                        and not cat.startswith("multi-unit"):
                    examples.append((m, text, street, city, pcity, zipc,
                                     res[0]["label"] + " " + res[0]["type"] if res else "-", cat))
    n = len(rows)
    nunit = sum(1 for r in rows if r[2])
    print(f"sample: {n} addresses ({nunit} with a unit; unit typed for ~half of those), "
          f"seed {a.seed}; success = top-1 within 10 m")
    for m in modes:
        print(f"  {m:<5} {ok[m]:4d}/{n} = {100.0 * ok[m] / n:5.1f}% within 10 m;  "
              f"right street+number (exact, <=500 m): {100.0 * addr_ok[m] / n:5.1f}%")
        for cat, c in fails[m].most_common():
            print(f"        miss {c:4d}  {cat}")
    for t, errs in interp_err.items():
        errs.sort()
        print(f"leave-one-out {t}: n={len(errs)} error median {errs[len(errs) // 2]:.0f} m, "
              f"p75 {errs[int(len(errs) * 0.75)]:.0f} m, p90 {errs[int(len(errs) * 0.9)]:.0f} m, "
              f"max {errs[-1]:.0f} m")
    lat_ms.sort()
    print(f"latency per query: median {lat_ms[len(lat_ms) // 2]:.1f} ms, "
          f"p95 {lat_ms[int(len(lat_ms) * 0.95)]:.1f} ms, max {lat_ms[-1]:.1f} ms")
    if examples:
        print("\nexample misses (mode | typed | source street | city | postal city | zip | top hit | category):")
        for e in examples:
            print("  " + " | ".join(str(v) for v in e))


if __name__ == "__main__":
    main()
