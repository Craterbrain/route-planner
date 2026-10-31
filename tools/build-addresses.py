#!/usr/bin/env python3
"""Build an offline address pack (SQLite) for one US state from Overture Maps.

    tools/build-addresses.py --state TN --out data/tn/addresses.sqlite
    tools/build-addresses.py --state KY --bbox -89.6,36.4,-81.9,39.2 --out data/ky/addresses.sqlite
    tools/build-addresses.py --selftest

Requires Python 3 + duckdb (pip install duckdb) for the build; --selftest needs
only the standard library. See docs/address-pack.md for schema, normalization
and the intended query flow.

Stages (each is atomic, so a re-run resumes after the last finished stage):
  1. fetch:  one streaming DuckDB query against the anonymous Overture S3
             bucket -> <out dir>/overture-<STATE>-<release>.parquet (cache;
             reused unless --refetch).
  2. build:  normalize + group in DuckDB/Python -> <out>.part SQLite file,
             indexes, ANALYZE, VACUUM, then rename to <out>.
"""

import argparse
import datetime as _dt
import json
import os
import sqlite3
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from addrnorm import normalize_street, normalize_number, split_house_number  # noqa: E402

PACK_FORMAT = 1
DEFAULT_RELEASE = "2026-10-21.0"
S3_PATH = "s3://overturemaps-us-west-2/release/{release}/theme=addresses/type=address/*"

# State bounding boxes (xmin, ymin, xmax, ymax), slightly padded. Used only for
# Parquet row-group pruning; the real filter is address_levels[1] = state.
STATE_BBOX = {
    "TN": (-90.34, 34.96, -81.62, 36.70),
}

ATTRIBUTION = (
    "Address data: Overture Maps Foundation (overturemaps.org), addresses theme, "
    "release {release}, derived from the National Address Database (NAD) from "
    "the U.S. Department of Transportation and the individual states that "
    "contribute to the database."
)
LICENSE_NOTE = (
    "Overture addresses theme carries per-source licenses (no single theme "
    "license). Per-record sources[].license for these rows: {licenses}. "
    "Overture attribution page (accessed 2026-10-31) states: \"The National "
    "Address Database is licensed under the National Address Database Access "
    "and Usage License.\" NAD disclaimer: "
    "https://www.transportation.gov/mission/open/gis/national-address-database/"
    "national-address-database-nad-disclaimer"
)

# Words kept upper case when prettifying ALL-CAPS NAD names for display.
KEEP_UPPER = {"US", "SR", "TN", "CR", "NE", "NW", "SE", "SW", "N", "S", "E", "W",
              "I", "II", "III", "IV", "PVT", "MLK"}


def log(msg, t0=[time.time()]):
    print(f"[{time.time() - t0[0]:7.1f}s] {msg}", flush=True)


def pretty_word(w):
    if not w.isupper() or w in KEEP_UPPER:
        return w
    if w[:1].isdigit():
        return w.lower()  # 12TH -> 12th, 19E -> 19e
    out, cap = [], True
    for c in w:
        out.append(c.upper() if cap else c.lower())
        cap = not c.isalnum()  # O'NEIL -> O'Neil, SMITH-JONES -> Smith-Jones
    return "".join(out)


def pretty(s):
    return " ".join(pretty_word(w) for w in (s or "").split())


# ---------------------------------------------------------------- self-test

def selftest():
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "address-normalize-cases.json"), encoding="utf-8") as f:
        cases = json.load(f)
    bad = 0
    for raw, want in [("8681", "8681"), ("12-B", "12b"), ("12 1/2", "121/2"), ("Rear", "rear")]:
        if normalize_number(raw) != want:
            bad += 1
            print(f"FAIL normalize_number({raw!r}) = {normalize_number(raw)!r}, want {want!r}")
    for raw, unit, want in [("8681", "", ("8681", 8681, "")), ("509 A", "", ("509", 509, "a")),
                            ("112B", "", ("112", 112, "b")), ("101 1/2", "", ("101", 101, "1/2")),
                            ("225 LOT", "LOT 13", ("225", 225, "13")),
                            ("509 B", "102", ("509", 509, "b102")), ("12", "Apt. 4-B", ("12", 12, "4b"))]:
        if split_house_number(raw, unit) != want:
            bad += 1
            print(f"FAIL split_house_number({raw!r}, {unit!r}) = "
                  f"{split_house_number(raw, unit)!r}, want {want!r}")
    for c in cases:
        got = normalize_street(c["input"])
        if got != c["norm"]:
            bad += 1
            print(f"FAIL {c['input']!r}: got {got!r}, want {c['norm']!r}")
    print(f"{len(cases) - bad}/{len(cases)} normalization cases pass")
    return 0 if bad == 0 else 1


# ---------------------------------------------------------------- stage 1

def fetch(con, state, release, bbox, cache):
    if os.path.exists(cache):
        log(f"fetch: reusing cached {cache}")
        return
    xmin, ymin, xmax, ymax = bbox
    part = cache + ".part"
    log(f"fetch: Overture {release} addresses, state={state}, bbox={bbox} -> {cache}")
    con.execute(f"""
        COPY (
          SELECT number, street, unit, postcode, postal_city,
                 [l.value FOR l IN address_levels] AS levels,
                 ST_X(geometry) AS x, ST_Y(geometry) AS y,
                 sources[1].dataset AS dataset, sources[1].license AS license,
                 sources[1].update_time AS update_time
          FROM read_parquet('{S3_PATH.format(release=release)}')
          WHERE bbox.xmin BETWEEN {xmin} AND {xmax}
            AND bbox.ymin BETWEEN {ymin} AND {ymax}
            AND country = 'US' AND address_levels[1].value = '{state}'
        ) TO '{part}' (FORMAT parquet, COMPRESSION zstd)""")
    os.replace(part, cache)
    log("fetch: done")


# ---------------------------------------------------------------- stage 2

SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE streets(
  id       INTEGER PRIMARY KEY,
  name     TEXT NOT NULL,      -- display name, e.g. 'Highway 70 East'
  norm     TEXT NOT NULL,      -- normalize_street(name), e.g. 'hwy 70 e'
  city     TEXT,               -- municipality (address_levels[2]) or postal city
  pcity    TEXT,               -- USPS postal city, e.g. 'Crossville'
  postcode TEXT,
  lat      INTEGER NOT NULL,   -- centroid, microdegrees
  lon      INTEGER NOT NULL
);
CREATE TABLE addr(
  street_id INTEGER NOT NULL,
  num       INTEGER NOT NULL,  -- leading digits of number, -1 if none
  number    TEXT NOT NULL,     -- split_house_number(): '8681' (letter/fraction suffixes go to unit)
  unit      TEXT NOT NULL,     -- normalize_unit(): '' when none, 'a', '1/2', '4b'
  lat       INTEGER NOT NULL,
  lon       INTEGER NOT NULL,
  PRIMARY KEY(street_id, num, number, unit)
) WITHOUT ROWID;
"""


def build(con, cache, out, state, release, bbox):
    part = out + ".part"
    if os.path.exists(part):
        os.remove(part)
    t_build = time.time()
    src = f"read_parquet('{cache}')"
    total = con.execute(f"SELECT count(*) FROM {src}").fetchone()[0]
    log(f"build: {total:,} source rows")

    # 1. normalize each distinct street name once (Python), hand back to DuckDB.
    names = [r[0] for r in con.execute(
        f"SELECT DISTINCT street FROM {src} WHERE street IS NOT NULL").fetchall()]
    log(f"build: normalizing {len(names):,} distinct street names")
    rows = [(n, normalize_street(n), pretty(n)) for n in names]
    con.execute("CREATE TEMP TABLE sn(street VARCHAR, norm VARCHAR, name VARCHAR)")
    # (bulk load through a temp TSV: far faster than executemany into DuckDB)
    tmp = out + ".names.tsv"
    with open(tmp, "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(x.replace("\t", " ") for x in r) + "\n")
    con.execute(f"INSERT INTO sn SELECT * FROM read_csv('{tmp}', delim='\t', "
                "header=false, quote='', escape='', "
                "columns={'street':'VARCHAR','norm':'VARCHAR','name':'VARCHAR'})")
    os.unlink(tmp)
    empty = sum(1 for r in rows if not r[1])
    log(f"build: {empty} names normalize to '' (dropped)")

    # 2. joined, cleaned rows.
    con.execute(f"""
      CREATE TEMP TABLE a AS
      SELECT sn.norm, sn.name,
             coalesce(nullif(trim(s.levels[2]), ''), s.postal_city) AS city_raw,
             s.postal_city AS pcity_raw,
             coalesce(s.postcode, '') AS postcode,
             s.number, coalesce(s.unit, '') AS unit_raw, s.x, s.y
      FROM {src} s JOIN sn ON s.street = sn.street
      WHERE sn.norm <> '' AND s.number IS NOT NULL AND s.x IS NOT NULL""")

    # 3. street groups: one row per (norm, city, postcode); display name and
    #    postal city = most common spelling; centroid = mean point.
    con.execute("""
      CREATE TEMP TABLE g AS
      SELECT row_number() OVER (ORDER BY norm, upper(city_raw), postcode) AS id,
             norm, upper(city_raw) AS cityk, postcode,
             mode(name) AS name, mode(city_raw) AS city, mode(pcity_raw) AS pcity,
             avg(y) AS lat, avg(x) AS lon
      FROM a GROUP BY norm, upper(city_raw), postcode""")
    nstreets = con.execute("SELECT count(*) FROM g").fetchone()[0]
    log(f"build: {nstreets:,} street groups")

    db = sqlite3.connect(part)
    db.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; "
                     "PRAGMA page_size=4096; PRAGMA cache_size=-400000;")
    db.executescript(SCHEMA)

    cur = con.execute("SELECT id, name, norm, city, pcity, postcode, lat, lon FROM g ORDER BY id")
    while True:
        batch = cur.fetchmany(50000)
        if not batch:
            break
        db.executemany("INSERT INTO streets VALUES (?,?,?,?,?,?,?,?)", [
            (i, nm, no, pretty(c), pretty(pc), pz or None,
             round(la * 1e6), round(lo * 1e6))
            for i, nm, no, c, pc, pz, la, lo in batch])
    log("build: streets written")

    # 4. address rows. House number / unit keys come from
    #    addrnorm.split_house_number(), run once per distinct (number, unit)
    #    pair; rows are deduplicated on the primary key (first point wins).
    pairs = con.execute(f"SELECT DISTINCT number, coalesce(unit, '') FROM {src} "
                        "WHERE number IS NOT NULL").fetchall()
    log(f"build: splitting {len(pairs):,} distinct (number, unit) pairs")
    tmp = out + ".nums.tsv"
    with open(tmp, "w", encoding="utf-8") as f:
        for number, unit in pairs:
            nn, num, un = split_house_number(number, unit)
            f.write("\t".join((number.replace("\t", " "), unit.replace("\t", " "),
                                nn, str(num), un)) + "\n")
    con.execute(f"CREATE TEMP TABLE nu AS SELECT * FROM read_csv('{tmp}', delim='\t', "
                "header=false, quote='', escape='', columns={'number_raw':'VARCHAR',"
                "'unit_raw':'VARCHAR','number':'VARCHAR','num':'BIGINT','unit':'VARCHAR'})")
    os.unlink(tmp)
    con.execute("UPDATE nu SET unit_raw = coalesce(unit_raw, ''), unit = coalesce(unit, ''), "
                "number = coalesce(number, '')")
    con.execute("""
      CREATE TEMP TABLE ad AS
      SELECT g.id AS street_id, nu.num, nu.number, nu.unit,
             CAST(round(a.y * 1e6) AS BIGINT) AS lat, CAST(round(a.x * 1e6) AS BIGINT) AS lon
      FROM a
      JOIN g ON a.norm = g.norm AND upper(a.city_raw) IS NOT DISTINCT FROM g.cityk
            AND a.postcode = g.postcode
      JOIN nu ON a.number = nu.number_raw AND a.unit_raw = nu.unit_raw
      WHERE nu.number <> ''
      QUALIFY row_number() OVER (PARTITION BY g.id, nu.num, nu.number, nu.unit
                                 ORDER BY lat, lon) = 1""")
    naddr = con.execute("SELECT count(*) FROM ad").fetchone()[0]
    cur = con.execute("SELECT * FROM ad ORDER BY street_id, num, number, unit")
    done = 0
    while True:
        batch = cur.fetchmany(250000)
        if not batch:
            break
        db.executemany("INSERT INTO addr VALUES (?,?,?,?,?,?)", batch)
        done += len(batch)
        log(f"build: addr {done:,}/{naddr:,}")
    log(f"build: {naddr:,} address rows written ({total - naddr:,} duplicates/invalid dropped)")

    db.execute("CREATE INDEX streets_norm ON streets(norm)")
    db.execute("CREATE INDEX streets_postcode ON streets(postcode)")

    lic = [r[0] for r in con.execute(
        f"SELECT DISTINCT coalesce(license, 'null') FROM {src} ORDER BY 1").fetchall()]
    ds = [r[0] for r in con.execute(
        f"SELECT DISTINCT coalesce(dataset, 'null') FROM {src} ORDER BY 1").fetchall()]
    upd = con.execute(f"SELECT min(update_time), max(update_time) FROM {src}").fetchone()
    meta = {
        "format": str(PACK_FORMAT),
        "source": "Overture addresses",
        "release": release,
        "datasets": ",".join(ds),
        "source_update_time": f"{upd[0]}..{upd[1]}",
        "state": state,
        "country": "US",
        "built_at": _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source_rows": str(total),
        "streets": str(nstreets),
        "addresses": str(naddr),
        "bbox": ",".join(str(v) for v in bbox),
        "coord_unit": "microdegrees (value * 1e6, WGS84)",
        "normalizer": "docs/address-pack.md#normalization v1",
        "attribution": ATTRIBUTION.format(release=release),
        "license": LICENSE_NOTE.format(licenses=", ".join(lic)),
    }
    db.executemany("INSERT INTO meta VALUES (?, ?)", sorted(meta.items()))
    db.commit()
    log("build: ANALYZE")
    db.execute("ANALYZE")
    db.execute(f"PRAGMA user_version = {PACK_FORMAT}")
    db.commit()
    log("build: VACUUM")
    db.execute("VACUUM")
    db.execute("PRAGMA journal_mode=DELETE")
    db.close()
    os.replace(part, out)
    log(f"build: wrote {out} ({os.path.getsize(out) / 1e6:.1f} MB) "
        f"in {time.time() - t_build:.0f}s")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--state", help="two-letter state code, e.g. TN")
    ap.add_argument("--out", help="output SQLite path, e.g. data/tn/addresses.sqlite")
    ap.add_argument("--release", default=DEFAULT_RELEASE)
    ap.add_argument("--bbox", help="xmin,ymin,xmax,ymax (required for states without a built-in bbox)")
    ap.add_argument("--refetch", action="store_true", help="ignore the cached Overture extract")
    ap.add_argument("--threads", type=int, default=os.cpu_count() or 4)
    ap.add_argument("--selftest", action="store_true", help="check normalization vectors and exit")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if not a.state or not a.out:
        ap.error("--state and --out are required")
    state = a.state.upper()
    if a.bbox:
        bbox = tuple(float(v) for v in a.bbox.split(","))
        if len(bbox) != 4:
            ap.error("--bbox needs 4 numbers")
    elif state in STATE_BBOX:
        bbox = STATE_BBOX[state]
    else:
        ap.error(f"no built-in bbox for {state}; pass --bbox xmin,ymin,xmax,ymax")
    if selftest() != 0:
        print("normalization self-test failed; refusing to build", file=sys.stderr)
        return 1

    import duckdb  # only needed for the build
    out = os.path.abspath(a.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    cache = os.path.join(os.path.dirname(out), f"overture-{state}-{a.release}.parquet")
    if a.refetch and os.path.exists(cache):
        os.remove(cache)
    con = duckdb.connect()
    con.execute(f"SET threads={a.threads}")
    con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; "
                "SET s3_region='us-west-2';")
    fetch(con, state, a.release, bbox, cache)
    build(con, cache, out, state, a.release, bbox)
    return 0


if __name__ == "__main__":
    sys.exit(main())
