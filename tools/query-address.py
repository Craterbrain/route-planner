#!/usr/bin/env python3
"""Reference offline address lookup against an address pack.

    tools/query-address.py data/tn/addresses.sqlite "8681 US-70"
    tools/query-address.py data/tn/addresses.sqlite "8681 hwy 70 east, Crossville TN 38555"
    tools/query-address.py data/tn/addresses.sqlite "100 N Main St" --near 36.17,-86.78

This file is the reference the Kotlin search is ported from, so it is written
as plain steps with no Python-only tricks. Standard library only. The flow is
documented in docs/address-pack.md ("Query flow"); several ideas (word tries,
half-typed last word, layered fallbacks, restricting interpolation to one
street group) come from Vela Maps' design (ideas only, no code reused).
"""

import argparse
import math
import os
import sqlite3
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from addrnorm import (normalize_street, normalize_place, split_house_number,  # noqa: E402
                      DIRECTIONALS, STREET_TYPES)

__all__ = ["search", "lookup_number", "split_house_number", "haversine_m"]

DIRS = set(DIRECTIONALS.values())
UNIT_WORDS = {"apt", "apartment", "unit", "ste", "suite", "#", "lot", "rm", "room", "bldg", "trlr"}
STATE_WORDS = {"tn", "tennessee", "usa", "us"}  # only stripped from the END of the text
MAX_CANDIDATE_STREETS = 300
NEAR_BAND_M = 25000  # within a match tier, results this close to --near rank first

# Match types, best first.
EXACT, INTERPOLATED, NEAREST, STREET = "exact", "interpolated", "nearest-house", "street"
TYPE_RANK = {EXACT: 0, INTERPOLATED: 1, NEAREST: 2, STREET: 3}


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def next_prefix(s):
    """Smallest string greater than every string starting with s (BINARY order)."""
    return s[:-1] + chr(ord(s[-1]) + 1)


# ----------------------------------------------------------------- parsing

def parse_query(text):
    """Split free text into house, street words, locality words, postcode.

    "8681 US Hwy 70 E, Crossville TN 38555" ->
        house ('8681', 8681, ''), street words ['US','Hwy','70','E'],
        locality words ['Crossville'], postcode '38555'
    Text after the first comma is locality only (never part of the street).
    """
    street_part, _, locality_part = text.partition(",")
    words = street_part.replace("#", " # ").split()
    loc_words = locality_part.replace(",", " ").split()

    number_raw = ""
    if words and words[0][:1].isdigit():
        number_raw = words[0]
        words = words[1:]

    # unit: "apt 4", "# 4", "unit B" anywhere after the street name
    # (scan from the END: "Highland Courtyard Apartment Loop apt 206" has a
    #  unit word inside the street name)
    unit_raw = ""
    for i in range(len(words) - 2, 0, -1):
        if words[i].lower().strip(".") in UNIT_WORDS:
            unit_raw = words[i + 1]
            words = words[:i] + words[i + 2:]
            break
    # same split as the build: "112B" -> number '112', unit 'b'
    number, num, unit = split_house_number(number_raw, unit_raw) if number_raw else ("", -1, "")

    # trailing ZIP and state words, from either part
    postcode = ""
    for lst in (loc_words, words):
        while lst:
            last = lst[-1].lower().strip(".")
            if len(last) in (5, 10) and last[:5].isdigit() and not postcode:
                postcode = last[:5]
                lst.pop()
            elif last in STATE_WORDS and (lst is loc_words or len(lst) > 1):
                lst.pop()
            else:
                break
    return (number, num, unit), words, loc_words, postcode


# ----------------------------------------------------------------- streets

def street_candidates(db, q, postcode):
    """Streets whose norm starts with q (last token may be half-typed)."""
    sql = ("SELECT id, name, norm, city, pcity, postcode, lat, lon FROM streets "
           "WHERE norm >= ? AND norm < ?")
    args = [q, next_prefix(q)]
    if postcode:
        sql += " AND postcode = ?"
        args.append(postcode)
    # bound the work for very short prefixes ("m"); 5000 groups is plenty
    sql += " LIMIT 5000"
    return db.execute(sql, args).fetchall()


def word_match(q_tokens, norm_tokens):
    """2 = every query token is a whole token of norm, in order from the start;
    1 = same but the last query token is only a prefix (half-typed word);
    0 = no match (e.g. 'hwy 70' vs 'hwy 701' still gives 1, ranked lower).
    A leading directional left in norm (rule 10 keeps it when the rest is all
    street-type words: 'East Cove Road' -> 'e cv rd') is skipped if the query
    does not start with it."""
    if norm_tokens and norm_tokens[0] in DIRS and q_tokens and q_tokens[0] != norm_tokens[0]:
        norm_tokens = norm_tokens[1:]
    if len(q_tokens) > len(norm_tokens):
        return 0
    for i, t in enumerate(q_tokens):
        if norm_tokens[i] == t:
            continue
        if i == len(q_tokens) - 1 and norm_tokens[i].startswith(t):
            return 1
        return 0
    return 2


def locality_ok(loc_tokens, street):
    """Every typed locality token appears in city, postal city or postcode
    (the last one may be half-typed: 'crossv' matches 'crossville')."""
    if not loc_tokens:
        return True
    have = set(normalize_place(street[3] or "")) | set(normalize_place(street[4] or ""))
    if street[5]:
        have.add(street[5])
    for i, t in enumerate(loc_tokens):
        if t in have:
            continue
        if i == len(loc_tokens) - 1 and any(h.startswith(t) for h in have):
            continue
        return False
    return True


def find_streets(db, words, loc_words, postcode):
    """Word tries: the street as typed, then with trailing words dropped one at
    a time (dropped words become locality words). Returns (streets, info)."""
    loc_typed = normalize_place(" ".join(loc_words))
    fallback = None
    for k in range(len(words), 0, -1):
        q = normalize_street(" ".join(words[:k]))
        if not q:
            continue
        qt = q.split()
        # A directional (typed first or last; normalize_street moves a leading
        # one to the end) is only a preference: search without it so
        # "Main St" / "N Main St" / "S Main St" all find each other.
        want_dir = ""
        if len(qt) > 1 and qt[-1] in DIRS:
            want_dir = qt[-1]
            qt = qt[:-1]
        rows = street_candidates(db, " ".join(qt), postcode)
        # Also look behind a leading directional (88 TN norms such as
        # 'e cv rd', 'n ct st' keep it in front), so "Cove Rd" and
        # "Cove Rd East" find 'e cv rd'. Such norms only ever contain
        # street-type and directional words, so only then is it worth the
        # extra range scan per directional.
        if qt[0] not in DIRS and all(t in STREET_TYPES or t in DIRS for t in qt):
            for d in sorted(DIRS):
                rows += street_candidates(db, d + " " + " ".join(qt), postcode)
        rows = [r for r in rows if word_match(qt, r[2].split())]
        if not rows:
            continue
        loc = loc_typed + normalize_place(" ".join(words[k:]))
        good = [r for r in rows if locality_ok(loc, r)]
        info = {"q": " ".join(qt), "dir": want_dir, "loc": loc, "k": k}
        if good:
            return good, dict(info, locality_ok=True)
        if fallback is None:
            fallback = (rows, dict(info, locality_ok=not loc))
    return fallback if fallback else ([], {})


# ----------------------------------------------------------------- numbers

def lookup_number(db, street_id, house):
    """Layers 1-3 inside ONE street group (never across city/postcode groups):
    exact house, interpolation between nearest same-parity numbers, nearest
    mapped house. Returns (type, lat, lon, detail) or None."""
    number, num, unit = house
    if num < 0:
        return None
    rows = db.execute("SELECT unit, lat, lon FROM addr WHERE street_id=? AND num=? AND number=?",
                      (street_id, num, number)).fetchall()
    if rows:
        # the typed unit, else the building row (unit ''), else the centroid
        # of all its units (apartment complex with only per-unit points)
        for want in ([unit] if unit else []) + [""]:
            for r in rows:
                if r[0] == want:
                    return EXACT, r[1] / 1e6, r[2] / 1e6, number + (" #" + r[0] if r[0] else "")
        lat = sum(r[1] for r in rows) / len(rows)
        lon = sum(r[2] for r in rows) / len(rows)
        return EXACT, lat / 1e6, lon / 1e6, f"{number}, centroid of {len(rows)} units"

    def side(parity_sql, args):
        lo = db.execute("SELECT num, lat, lon FROM addr WHERE street_id=? AND num>=0 AND num<?"
                        + parity_sql + " ORDER BY num DESC LIMIT 1", (street_id, num) + args).fetchone()
        hi = db.execute("SELECT num, lat, lon FROM addr WHERE street_id=? AND num>?"
                        + parity_sql + " ORDER BY num ASC LIMIT 1", (street_id, num) + args).fetchone()
        return lo, hi

    lo, hi = side(" AND num % 2 = ?", (num % 2,))      # same side of the street
    if lo is None or hi is None:
        lo2, hi2 = side("", ())                          # any side
        lo = lo or lo2
        hi = hi or hi2
    if lo and hi:
        f = (num - lo[0]) / (hi[0] - lo[0])
        lat = lo[1] + f * (hi[1] - lo[1])
        lon = lo[2] + f * (hi[2] - lo[2])
        return INTERPOLATED, lat / 1e6, lon / 1e6, f"between {lo[0]} and {hi[0]}"
    one = lo or hi
    if one:
        return NEAREST, one[1] / 1e6, one[2] / 1e6, f"nearest mapped number {one[0]}"
    return None


# ----------------------------------------------------------------- search

def search(db, text, near=None, limit=5):
    house, words, loc_words, postcode = parse_query(text)
    results = search_parsed(db, house, words, loc_words, postcode, near)
    # "1052 A West Side Rd" / "101 1/2 Main St": a lone non-directional letter
    # or a fraction right after the number may be a unit, or a street name
    # ("198 C Street"). Try both readings and keep the better-ranked hits.
    if house[0] and len(words) > 1 and (
            (len(words[0]) == 1 and words[0].isalpha() and words[0].lower() not in "nsew")
            or "/" in words[0]):
        alt = split_house_number(house[0] + " " + words[0], house[2])
        results += search_parsed(db, alt, words[1:], loc_words, postcode, near)
    results.sort(key=lambda r: r["key"])
    seen, out = set(), []
    for r in results:
        k = (r["street_id"], r["lat"], r["lon"])
        if k not in seen:
            seen.add(k)
            out.append(r)
    return out[:limit]


def search_parsed(db, house, words, loc_words, postcode, near):
    number = house[0]
    streets, info = find_streets(db, words, loc_words, postcode)
    if not streets and postcode:  # ZIP may be wrong/outdated: retry without it
        streets, info = find_streets(db, words, loc_words + [postcode], "")
    if not streets:
        return []

    def dist(lat, lon):
        return haversine_m(near[0], near[1], lat, lon) if near else 0.0

    # cap the candidate list: nearest street groups first when we know where
    # the user is, else exact-name matches first
    if near:
        streets.sort(key=lambda s: dist(s[6] / 1e6, s[7] / 1e6))
    else:
        streets.sort(key=lambda s: -word_match(info["q"].split(), s[2].split()))
    streets = streets[:MAX_CANDIDATE_STREETS]

    results = []
    qt = info["q"].split()
    for s in streets:
        hit = lookup_number(db, s[0], house) if number else None
        if hit is None:
            hit = (STREET, s[6] / 1e6, s[7] / 1e6, "street centroid")
        mtype, lat, lon, detail = hit
        st = s[2].split()
        dir_ok = (not info["dir"]) or (info["dir"] in st)
        d = dist(lat, lon)
        # Match quality before distance: an exact house never loses to a nearby
        # estimate. Within a tier, houses within NEAR_BAND_M of --near come first
        # (same street + number in another town), then street fit, then distance.
        band = 0 if d < NEAR_BAND_M else 1
        key = (
            0 if info["locality_ok"] else 1,  # a typed city/ZIP that disagrees ranks last
            TYPE_RANK[mtype],
            band,
            0 if dir_ok else 1,
            2 - word_match(qt, st),       # whole words before half-typed
            len(st),                      # 'main st' before 'main st n' when undirected
            d,
        )
        results.append({"key": key, "type": mtype, "lat": round(lat, 6), "lon": round(lon, 6),
                        "label": f"{number + ' ' if number else ''}{s[1]}, "
                                 f"{s[3] or ''} {s[5] or ''}".strip(),
                        "pcity": s[4], "detail": detail, "street_id": s[0], "norm": s[2],
                        "dist_m": round(d) if near else None})
    return results


def main():
    ap = argparse.ArgumentParser(description="Look up an address in an offline address pack.")
    ap.add_argument("pack")
    ap.add_argument("query", nargs="+")
    ap.add_argument("--near", help="lat,lon to rank by distance")
    ap.add_argument("--limit", type=int, default=5)
    a = ap.parse_args()
    db = sqlite3.connect(f"file:{a.pack}?mode=ro", uri=True)
    near = tuple(float(v) for v in a.near.split(",")) if a.near else None
    for text in a.query:
        t = time.perf_counter()
        res = search(db, text, near, a.limit)
        ms = (time.perf_counter() - t) * 1000
        print(f"{text!r}  ({len(res)} results, {ms:.1f} ms)")
        for r in res:
            extra = f"  {r['dist_m']} m" if r["dist_m"] is not None else ""
            print(f"  {r['type']:<14} {r['lat']:.6f},{r['lon']:.6f}  {r['label']}"
                  f"  [{r['pcity']}] ({r['detail']}){extra}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
