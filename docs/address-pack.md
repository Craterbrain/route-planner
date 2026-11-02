# Offline address pack

An offline address pack is one read-only SQLite file, `addresses.sqlite`, that
covers one US state. It holds every house-level address point Overture Maps
publishes for that state. With it, the app can geocode typed addresses on the
phone: no network, no API key, no Photon. It fills the gap where OSM has no
house numbers, which is most rural US addresses.

The approach is the same as Vela Maps' offline mode: house points from an
address dataset, with interpolation when an exact number is missing. Several
query-flow ideas below come from Vela's design (one SQLite per region,
street names stored once, layered fallbacks, word tries, prefix match on a
half-typed last word, and its documented weakness of interpolating across
towns). We took ideas only. No Vela code was used.

| | |
|---|---|
| Builder | `tools/build-addresses.py` (Python 3 + `duckdb`) |
| Normalizer | `tools/addrnorm.py` (stdlib only), vectors in `tools/address-normalize-cases.json` |
| Reference lookup | `tools/query-address.py` (stdlib only), the code the Kotlin port follows |
| Evaluation | `tools/eval-address-pack.py` (needs `duckdb`) |
| Tests | `python3 tools/test_normalize.py`, `python3 tools/build-addresses.py --selftest` |
| Output | `data/<state>/addresses.sqlite` (`data/` is gitignored) |

## Data source and license

- **Source:** Overture Maps Foundation, `addresses` theme, release
  `2026-10-21.0`. It is read anonymously from
  `s3://overturemaps-us-west-2/release/2026-10-21.0/theme=addresses/type=address/*`
  (region `us-west-2`).
- **Tennessee (verified):** all 3,928,492 rows have `sources[1].dataset = 'NAD'`
  (the US DOT National Address Database), NAD version `20260930`, and
  `update_time` `2026-10-05T16:07:53.567Z`. The per-record license field is
  `sources[1].license = 'LicenseRef-Public-Domain-US'` on every row.
- **Theme license (verified on docs.overturemaps.org/attribution and
  /guides/addresses, 2026-10-31):** the addresses theme has **no single
  license**, so it is *not* CDLA-Permissive-2.0 as first assumed. The guide
  says: "Because it aggregates many independent datasets, the theme carries a
  variety of source licenses rather than a single overall license." The US
  entry on the attribution page reads, verbatim:

  > National Address Database (NAD) from the U.S. Department of Transportation
  > and the individual states that contribute to the database. National
  > Address Database (NAD) Disclaimer. The National Address Database is
  > licensed under the National Address Database Access and Usage License.
  > Accessed: 2026-10-15.

- **Resolved:** the NAD is a work of the US federal government and as such is
  not subject to copyright protection under 17 U.S.C. § 105. It is available
  for re-use without limitation or restriction (confirmed by the project owner,
  2026-11-01). The packs can be redistributed. The attribution string below is
  kept as a courtesy and to credit Overture and the states that contribute data.
- **Attribution string** (stored in `meta.attribution`; show it on the
  Settings/About screen next to the OSM and Valhalla credits):

  > Address data: Overture Maps Foundation (overturemaps.org), addresses
  > theme, release 2026-10-21.0, derived from the National Address Database
  > (NAD) from the U.S. Department of Transportation and the individual states
  > that contribute to the database.

## Schema (pack format 1)

`PRAGMA user_version = 1`. Coordinates are WGS84 **microdegrees** stored as
integers (`round(deg * 1e6)`, about 0.1 m resolution).

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
-- keys: format, source, release, datasets, source_update_time, state, country,
--       built_at, source_rows, streets, addresses, bbox, coord_unit,
--       normalizer, attribution, license

CREATE TABLE streets(
  id       INTEGER PRIMARY KEY,
  name     TEXT NOT NULL,     -- display, e.g. 'Highway 70 East'
  norm     TEXT NOT NULL,     -- normalize_street(name), e.g. 'hwy 70 e'
  city     TEXT,              -- municipality (address_levels[2]) else postal city
  pcity    TEXT,              -- USPS postal city (Overture postal_city)
  postcode TEXT,
  lat      INTEGER NOT NULL,  -- centroid of the group's address points
  lon      INTEGER NOT NULL
);
CREATE INDEX streets_norm ON streets(norm);         -- BINARY collation: prefix ranges
CREATE INDEX streets_postcode ON streets(postcode);

CREATE TABLE addr(
  street_id INTEGER NOT NULL,  -- streets.id
  num       INTEGER NOT NULL,  -- leading digits of number; -1 if none
  number    TEXT NOT NULL,     -- '8681' (suffixes such as 'A', '1/2' move to unit)
  unit      TEXT NOT NULL,     -- normalize_unit(); '' when none
  lat       INTEGER NOT NULL,
  lon       INTEGER NOT NULL,
  PRIMARY KEY(street_id, num, number, unit)
) WITHOUT ROWID;
```

How this differs from the first sketch:

- **One `streets` row per (`norm`, city, postcode).** Spelling variants in the
  source, such as "HIGHWAY 70 East" and "Highway 70 East", merge into one row.
  The display name is the most common spelling.
- **`pcity` was added.** NAD's municipality ("Crab Orchard") and the USPS
  postal city ("Crossville") are often different, and people type either one.
- **`num` is part of the primary key and is `NOT NULL`.** It uses -1 instead
  of NULL, because a WITHOUT ROWID primary key cannot hold NULL. That one
  B-tree then serves both exact lookups and nearest-number scans, so no
  second index is needed. In Tennessee every row has a numeric `num`.
- `unit` is `''` rather than NULL, for the same reason.
- There is no FTS5. Every search is a B-tree range on `streets.norm`.

Build-time settings are `journal_mode=OFF`, `synchronous=OFF` and a large
cache, followed by `ANALYZE` (which writes stat1 and stat4) and `VACUUM`. The
shipped file has `journal_mode=DELETE`, and the app should open it read-only.

## Normalization

The app must compute the same `norm` for the text the user types as the
build computed for the data. The rules are defined here. `tools/addrnorm.py`
implements them, and `tools/address-normalize-cases.json` (124 cases) is the
contract: a Kotlin port is correct when it passes every vector. Each rule is
a character test, a whitespace split or a table lookup, with no regex.

`normalize_street(s)`:

1. **Fold:** apply Unicode NFKD, then drop every character in category `Mn`
   (combining marks). "Café" becomes "Cafe". In Kotlin:
   `Normalizer.normalize(s, NFKD)`, then drop `Character.NON_SPACING_MARK`.
2. **Lowercase** with root/ASCII rules (`lowercase(Locale.ROOT)`).
3. **Characters:** delete `' ’ ‘ ` ´ .` outright, so "O'Neil" becomes "oneil"
   and "U.S." becomes "us". Turn `&` into ` and `. Turn every other character
   that is not `a-z` or `0-9` into a space. Then split on whitespace.
4. **Split letter/digit runs:** a token of letters followed by digits
   splits in two ("us70" becomes "us 70", "i40" becomes "i 40"). A token of
   digits followed by letters also splits ("19e" becomes "19 e"), except when
   the letters are `st`, `nd`, `rd` or `th`, so "1st" and "22nd" stay whole.
5. Strip leading zeros from all-digit tokens ("070" becomes "70").
6. The pair `u s` becomes `us` (from "U. S.").
7. **Token tables**, applied in this order: ordinal words ("first" to "1st",
   up to "nineteenth", plus "twentieth" … "ninetieth"), then directionals
   (north→n, northeast→ne, …), then `WORD_MAP` (street types and a few name
   words). After that, a tens word followed by 1st–9th merges into one token
   ("twenty first" becomes "21st"). The tables are in `addrnorm.py`. Their
   canonical outputs are: aly ave bnd blvd byp cir ct cv crk xing dr expy ext
   fwy hts hwy holw ln loop pkwy pike pl plz pt rdg rd rte sq st ter trce trl
   tpke way, plus `saint→st`, `mount→mt`, `mountain→mtn`, `fort→ft`,
   `county→co`, `interstate→i`.
   - **"St" (Street) and "St" (Saint) both map to `st`.** The code never has
     to decide which one it is, and "St Mary St", "Saint Mary Street" and
     "St. Mary St." all become `st mary st`.
8. **Routes:** a run of one or more route words
   `{us, hwy, sr, tn, state, rte}` (after step 7) followed by an all-digit
   token becomes `hwy <n>`. **County roads:** `co rd|hwy|rte <n>` and
   `cr <n>` become `cr <n>`. Interstates stay as `i <n>`.
9. Drop a street-type token that repeats the token before it ("Rd Road"
   becomes `rd`).
10. **Leading directional:** if the first token is a directional and at
    least one later token is neither a street type nor a directional, move
    the directional to the end. "N Main St" and "Main St North" both become
    `main st n`. "North St" stays `n st`.
11. Join the tokens with single spaces.

`normalize_street` is idempotent: running it on its own output returns the
same string, and the tests check this.

Examples:

| typed | norm |
|---|---|
| US-70 E · US Hwy 70 East · Highway 70 East · E Highway 70 · Hwy 70 E | `hwy 70 e` |
| SR-70 · TN 70 · State Route 70 · U.S. 70 · Route 70 | `hwy 70` |
| HWY 19E · US-19 E | `hwy 19 e` |
| County Road 12 · CR-12 · Co Rd 12 | `cr 12` |
| Old Highway 70 | `old hwy 70` |
| Twenty-First Ave S · 21st Avenue South | `21st ave s` |
| West End Avenue | `end ave w` |

**Route decision and its trade-off.** NAD writes routes in generic forms
("HIGHWAY 70 East", "HWY 91", "OLD STATE ROUTE 34"). It almost never says
whether a road is a US route or a state route. So every route form collapses
to `hwy N`. The cost is that a US route and a state route with the same
number in different parts of the state (US-70 and TN-70, say) share a norm.
The search ranks by distance to `--near` (the route start or the phone's
position) and by the typed city/ZIP, and in practice that tells them apart.
If a future source keeps US and state routes distinct, adding a `us` or `sr`
qualifier token would need pack format 2.

Other helpers, defined in the same file and used by build and query:

- `normalize_place(s)` returns the tokens of a city name (steps 1–7 only).
  The query compares typed city words against `city` and `pcity` with it.
- `normalize_unit(s)` lowercases, turns punctuation into spaces, drops the
  words apt/apartment/unit/ste/suite/lot/rm/room/no/num/bldg/building/trlr/spc/space,
  and joins the rest with no separator ("Apt. 4-B" becomes `4b`).
- `split_house_number(number, unit)` returns `(number, num, unit)`. The house
  number keeps its first word only. A letter suffix glued to the digits, and
  any extra words, move to the unit: "509 A" becomes (`509`, 509, `a`),
  "112B" becomes (`112`, 112, `b`), "101 1/2" becomes (`101`, 101, `1/2`).
  About 16k Tennessee rows have suffixes like these, including "225 LOT" and
  interstate mile markers such as "224 MM".

## Query flow

`tools/query-address.py` implements this flow:

1. **Parse** (`parse_query`).
   - Text after the first comma is locality only.
   - A leading word that starts with a digit is the house number.
   - `apt|unit|ste|suite|#|lot … X` sets the unit. The last such word
     wins, scanning from the end, because street names can contain one
     ("Highland Courtyard Apartment Loop apt 206").
   - A trailing 5-digit ZIP becomes a postcode filter, and trailing
     `tn`/`tennessee`/`usa` is dropped.
   - The house number goes through `split_house_number`, the same function
     the build uses.
2. **Word tries** (`find_streets`). For k = all street words down to 1:
   - `q = normalize_street(words[:k])`. If q ends in a directional, strip it
     and keep it as a preference only, so "Main St", "N Main St" and
     "S Main St" find each other.
   - Run a range scan:
     `SELECT … FROM streets WHERE norm >= :q AND norm < :q_next [AND postcode = :zip]`,
     where `q_next` is q with its last character incremented. This is a
     plain prefix range on a BINARY index. It also matches a half-typed last
     word: "old hickory bl" finds `old hickory blvd`.
   - Keep only rows whose tokens start with the query tokens: whole words,
     except the last, which may be a prefix. A leading directional in the row
     is skipped when the query does not start with it (see the next point).
   - **Leading-directional variants.** Rule 10 leaves a leading directional
     in front when everything after it is a street-type word: "East Cove
     Road" becomes `e cv rd`, "North Court St" becomes `n ct st`. That is 88
     Tennessee norms. If every query token is a street type or a directional,
     also scan `"<d> " + q` for each directional d, so "Cove Rd" and
     "Cove Rd East" find `e cv rd`. The scan is skipped for every other
     query, so it costs nothing in the usual case. This is a query-side fix,
     so the norm contract and the vectors are unchanged.
   - The dropped words `words[k:]`, plus the text after the comma, must all
     appear among the tokens of `city`, `pcity` or `postcode`. The last of
     them may be a prefix ("crossv").
   - The first k with rows that satisfy the locality wins. If no k satisfies
     it, use the longest k that matched a street and mark the result
     "locality mismatch". If a typed ZIP matches nothing, retry with the ZIP
     treated as a locality word.
   - Ambiguity: "1052 A West Side Rd" can mean unit A of 1052, or house 1052
     on a street called "A …" (there really is a "198 C Street"). When a
     lone non-directional letter or a fraction follows the number, both
     readings are searched and the results merged.
3. **Cap the candidates.** With `--near`, keep the 300 street groups whose
   centroids are closest. Without it, put exact-name groups first and keep
   300.
4. **House number, inside one street group only** (`lookup_number`). The
   group is never mixed with another (city, postcode) group. This avoids the
   weakness Vela documents, where a same-named street in another town skews
   the interpolation.
   1. **exact:**
      `SELECT unit, lat, lon FROM addr WHERE street_id=? AND num=? AND number=?`.
      Take the typed unit if it is there, else the building row (unit `''`).
      If the address has only per-unit rows, take their centroid.
   2. **interpolated:** find the nearest lower and the nearest higher number
      on the same parity (same side of the street), then interpolate
      linearly by number:
      `… WHERE street_id=? AND num>=0 AND num<? AND num%2=? ORDER BY num DESC LIMIT 1`
      and the mirror query (`num>?`, `ORDER BY num ASC`). If either side is
      missing on this parity, fill it from any parity.
   3. **nearest-house:** if there are numbers on only one side, return the
      closest mapped house on that street (Vela's "any mapped house" layer).
   4. **street:** if no number was typed, return the group's centroid.
   5. Not implemented: snapping to the nearest point on the street
      centerline (Vela's fourth layer). The pack has no centerline geometry.
      It could come later from OSM ways in the Valhalla extract or from
      Overture transportation segments.
5. **Rank.** Sort by this key, in order:
   1. the locality matched (a typed city or ZIP that contradicts a result
      puts it last);
   2. match type (exact, then interpolated, then nearest-house, then
      street), so an exact house never loses to a nearby estimate;
   3. within 25 km of `--near` (`NEAR_BAND_M`) before farther. This keeps
      the same street and number in another town apart without letting an
      estimate outrank an exact house;
   4. the typed directional matches;
   5. whole words before a half-typed last word;
   6. fewer tokens ("main st" before "main st n" when no directional was
      typed);
   7. distance.

   Results are de-duplicated and the top N returned, each with lat/lon,
   label, postal city, match type and detail ("between 8299 and 9289").

Example:

```
$ tools/query-address.py data/tn/addresses.sqlite "8681 US-70" "8681 highway 70 crab orchard"
'8681 US-70'  (5 results, 5.9 ms)
  exact          35.909184,-84.898383  8681 Highway 70 East, Crab Orchard 38555  [Crossville] (8681)
  interpolated   35.245497,-89.773262  8681 US Highway 70, Bartlett 38002  [Bartlett] (between 8668 and 8751)
  ...
'8681 highway 70 crab orchard'  (2 results, 0.9 ms)
  exact          35.909184,-84.898383  8681 Highway 70 East, Crab Orchard 38555  [Crossville] (8681)
```

## Measured (Tennessee, this PC, 2026-10-31)

| | |
|---|---|
| Source rows | 3,928,492 (NAD only) |
| Street groups | 197,983 (118,922 distinct raw street names) |
| Address rows | 3,890,861 (37,631 exact duplicates on the primary key dropped; 559,290 rows carry a unit) |
| File size | **125.3 MB**: `addr` 104.1 MB, `streets` 14.5 MB, `streets_norm` 4.1 MB, `streets_postcode` 2.7 MB |
| Fetch | ~50 s, one streaming DuckDB query to S3, 65 MB zstd Parquet cache |
| Build | ~7.5 s from the cache (normalize, group, write, ANALYZE, VACUUM); peak RSS 1.6 GB |
| Query latency | median 0.2 ms, p95 1.2–2.0 ms, max 15–160 ms (very short prefixes such as "100 m" or common names statewide). Python + SQLite on a desktop. The phone will be slower, perhaps 5–10×, and Kotlin has less overhead than Python. |

**Match rates.** `tools/eval-address-pack.py` takes 300 random real
addresses (seeds 1–3). It rewrites them the way people type: 60% lowercase;
street types randomly expanded or abbreviated; directionals abbreviated
(45%), kept (25%) or dropped (30%); `HIGHWAY N` turned into US-N, US Hwy N,
SR-N, TN N and similar; and for rows with a unit, the unit typed half the
time. Each address is tried in four modes:

| mode | top-1 within 10 m of the source point | right street + number (exact, ≤ 500 m) |
|---|---|---|
| + city (`… crossville`) | 90.7–93.0% | 98.3–100% |
| + ZIP (`…, 38555`) | 90.3–93.0% | 99.3–99.7% |
| + `--near` (point ≤ 5 km away) | 90.7–93.0% | 98.7–100% |
| bare (street only, statewide) | 86.7–90.7% | 92.3–97.0% |

Where the remaining 10-metre misses come from (seed 1, city mode, 21
misses):

- **16 are multi-unit addresses where the unit was not typed.** The app
  returns the building point or the centroid of the units. 9 of those land
  within 50 m.
- **3 are ambiguous in the source.** The same number, unit and ZIP appears
  at several points >10 m apart (apartment complexes where every building has
  a "202"). Nothing in the data can tell them apart.
- **1 is a dropped directional.** "303 Cheatham St" returned East Cheatham,
  but the address is on West Cheatham. Both exist in the same town. This is a
  real ambiguity; `--near` resolves it only when the start is closer to the
  right one.
- **1 is a mile-marker address.** NAD has "244 MM", "I40 West". Typed as
  "244 MM I40 W", the query does not find it, because `mm` is read as a
  street word.

In bare mode, the extra misses are the same street name and number in
another town ("120 Main St" exists in dozens of towns). The city, ZIP or
`--near` fixes these, so the app should always pass the route start or the
phone's position.

**Interpolation accuracy** (leave-one-out: real addresses treated as missing
and estimated from their neighbours on the same street, n ≈ 260 per seed):
median error 14–21 m, p75 50–70 m, p90 100–170 m, max ~670 m. The
nearest-house fallback (numbers on only one side) is much worse: median
50–105 m, p90 290–460 m, max ~1.2 km.

## Regional packs (county / ZIP subset)

A regional pack has the same file format, schema, `user_version = 1` and
`meta.format = '1'` as a state pack, so the app imports it the same way. It
holds only the addresses a driver in one area needs.

```
tools/build-addresses.py --state TN --county "Cumberland County" \
    --region-name "Cumberland County, TN" --out data/cumberland/addresses.sqlite
# options: --zip-threshold 0.25 (default)   --zips 38555,38572 (extra/explicit ZIPs)
```

**What goes in:**

1. **County boundary.** The county polygon comes from Overture divisions
   (`theme=divisions/type=division_area`, `subtype='county'`,
   `class='land'`, `region='US-<ST>'`, `names.primary` = the county). There
   must be exactly one match, otherwise the build stops. The polygon is
   cached as `data/<st>/overture-county-<ST>-<slug>-<release>.wkt` (first
   line is the division id, the rest is WKT). For Cumberland County, TN that
   is division `9033d94d-6c71-4d41-ab17-62da9859d3f9`, bbox
   −85.272…−84.681, 35.747…36.171. The `US-TN` region filter rules out the
   KY, PA, NJ, VA and other Cumberland Counties.
2. **ZIP share.** Every statewide address is tested with
   `ST_Contains(county, point)`. For each ZIP the build prints its in-county
   count, statewide total and share.
3. **Rows kept:**
   - ALL addresses of every ZIP with share ≥ `--zip-threshold`, so a ZIP
     that straddles the county line stays whole and searchable; plus
   - every address inside the polygon, whatever its ZIP says. This covers
     the in-county corners of ZIPs below the threshold, rows with no ZIP, and
     typo ZIPs.
   - A "ZIP" counts as a ZIP only if it has 5 digits and at least 25
     statewide addresses. NAD has strays such as `3855`, `8574` and `39558`
     (a Mississippi ZIP) on a handful of in-county rows. Those rows are kept
     through the polygon, but they never pull in a whole ZIP.
4. **Statewide cache.** The build reuses the statewide Parquet cache in the
   sibling `data/<st>/` directory (or `--cache`). If that cache is missing,
   it runs the normal statewide fetch first.
5. **Meta.** `meta.state` stays `TN`. The extra keys are `region_name`
   ('Cumberland County, TN'), `region_type` (`county`), `region_county`,
   `region_boundary` (source and division id), `region_threshold`,
   `region_zips` (the ZIPs included whole), `region_partial_zips` (ZIPs only
   partly in the pack) and `region_inside_county`. `bbox` is the bbox of the
   points actually in the pack. State packs now also write `region_name`
   (for example 'Tennessee').

**Cumberland County, TN (measured 2026-10-31):**

| ZIP | in county | ZIP total (TN) | share | in pack |
|---|---|---|---|---|
| 38555 Crossville | 14,586 | 14,734 | 99.0% | whole ZIP |
| 38571 Crossville | 9,971 | 9,971 | 100% | whole ZIP |
| 38572 Crossville | 9,024 | 9,225 | 97.8% | whole ZIP |
| 38558 Crossville / Fairfield Glade | 7,514 | 7,515 | 100% | whole ZIP |
| 37723 Crab Orchard | 958 | 958 | 100% | whole ZIP |
| 37337 Grandview | 377 | 1,046 | 36.0% | whole ZIP |
| 37854 Rockwood | 1,522 | 7,902 | 19.3% | in-county part only |
| 38574 Monterey | 1,025 | 6,277 | 16.3% | in-county part only |
| 38583 Sparta | 911 | 14,423 | 6.3% | in-county part only |
| 37367 Pikeville | 62 | 7,036 | 0.9% | in-county part only |
| 37737, 38573, 38553, 38582 | 4, 2, 1, 1 | | ≤0.1% | in-county part only |
| no ZIP / stray ZIPs | 30 + 29 | | | in-county rows only |

- **Pleasant Hill:** 38578 does not appear in NAD. Pleasant Hill addresses
  are filed under 38571, 38572 and 38583.
- **Size:** 46,015 addresses lie inside the county. The pack has 47,034
  source rows, 46,715 address rows and 3,389 street groups.
- **File:** **1.6 MB** (`addr` 1.18 MB, `streets` 0.26 MB, indexes
  0.12 MB). gzip shrinks it to 0.73 MB if it is ever shipped compressed.
- **Build time:** 0.6 s from the caches. The first run adds about 16 s to
  fetch the county polygon.
- **Lowering the threshold:** `--zip-threshold 0.15` would add Rockwood and
  Monterey whole, for about +11.6k rows.

**Size tuning, checked:**

- page_size makes almost no difference. 512 B gives 1.63 MB, 1 KB 1.59,
  2 KB 1.57, 4 KB 1.59, 8 KB 1.61 and 64 KB 2.03, so the default 4096 stays.
- Integer columns are already variable-length in SQLite, so microdegree ints
  take 4 bytes each and small ids take 1–2 bytes.
- The remaining space is the WITHOUT ROWID `addr` B-tree, which is the data
  itself. Shrinking further would need schema changes such as delta-coded
  coordinates, which the Kotlin reader would not understand, so they were not
  made.

**Android compatibility:** the header has file-format read/write version 1
(rollback journal, not WAL) and schema format 4, and the text encoding is
UTF-8. The newest feature used is WITHOUT ROWID (SQLite 3.8.2). Android 8.0
ships SQLite 3.18, so it can open the file. `sqlite_stat4` is an ordinary
table to builds without STAT4.

**Match rates** (`tools/eval-address-pack.py … --polygon data/tn/overture-county-TN-cumberland-county-2026-10-21.0.wkt`):
300 addresses sampled inside the county, seeds 1–3, same perturbations as
for the state pack.

| mode | top-1 within 10 m | right street + number (exact, ≤ 500 m) |
|---|---|---|
| + city | 95.7–98.7% | 100% |
| + ZIP | 95.7–98.3% | 100% |
| + `--near` | 95.7–98.3% | 99.7–100% |
| bare | 95.7–98.3% | 100% |

- **Remaining misses:** multi-unit addresses where the unit was not typed
  (all but one within 50 m), Fairfield Glade condos where the same number
  appears at several points, and one ZIP-typo duplicate group (`39558`
  instead of `38558`).
- **Latency:** median 0.1 ms, max under 1 ms.
- **Interpolation (leave-one-out):** median error 21–26 m, p90 74–93 m.
- **Verified:** '8681 US-70', '8681 hwy 70 east', '8681 highway 70 crab
  orchard', '8681 US Highway 70 E Crossville' and '8681 Highway 70 East,
  Crossville TN 38555' all return the exact point 35.909184, −84.898383.

**Caveats:**

- Addresses just outside the county are present only when their ZIP is
  included whole. A delivery in Rockwood or Monterey proper, outside the
  county, is not in the pack.
- ZIPs are taken from NAD as they are. A few rows have typo ZIPs, and a
  ZIP's real USPS extent can differ from where its NAD points fall.
- Shares are computed against **Tennessee** addresses only. A ZIP crossing a
  state line is judged by its TN part.

## Building for another state

```
tools/build-addresses.py --state KY --bbox -89.6,36.4,-81.9,39.2 --out data/ky/addresses.sqlite
```

- **Requirements:** Python 3 and `pip install duckdb`. A venv at
  `tools/.venv` works; `tools/.gitignore` ignores it.
  The script installs the `httpfs` and `spatial` DuckDB extensions on first
  use.
- **States and bboxes:** Tennessee has a built-in bbox (`STATE_BBOX`). For
  other states pass `--bbox`. The bbox only prunes Parquet row groups; the
  real filter is `address_levels[1] = '<STATE>'`. Add each new state to the
  table once its bbox is known.
- **Restartable:** the fetch writes
  `data/<st>/overture-<ST>-<release>.parquet` atomically and is reused on
  later runs (`--refetch` forces a new one). The build writes
  `<out>.part` and renames it at the end, so an interrupted run never leaves
  a half-written pack.
- **Other options:** `--release` picks a newer Overture release. The build
  refuses to run if the normalization self-test fails.
- **Coverage:** Overture's US addresses are NAD, and NAD coverage varies a
  lot by state. Some states contribute nothing. Check
  `SELECT count(*) … WHERE address_levels[1].value = 'XX'` before promising
  coverage.
- **When normalization changes:** bump `PACK_FORMAT` and `user_version`,
  update the vectors, and rebuild every pack.

## How the app will use it

- **Import:** Settings → import address pack, the same flow as the Valhalla
  tiles. Copy the file to `filesDir/addresses.sqlite`.
- **Validation on import:** check `PRAGMA user_version == 1` and
  `meta.format == '1'`, then show `meta.region_name`, falling back to
  `meta.state` for packs built before region support. Also show
  `meta.release`, `meta.addresses` and `meta.attribution`.
- **Opening:** use `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)`.
  Do not enable WAL or write anything.
- **Search order:** try the offline pack first whenever its state covers the
  route area. Fall back to the platform `Geocoder` and Photon when the
  network is up and the pack has no exact or interpolated hit.
- **Kotlin port:**
  - `normalizeStreet`, `normalizePlace`, `normalizeUnit` and
    `splitHouseNumber`, plus a JUnit test that loads
    `tools/address-normalize-cases.json`. Copy the file into `core`'s test
    resources, or read it from the repo path.
  - `parseQuery`, the word tries, `lookupNumber` and the ranking key, line
    for line from `query-address.py`.
  - All coordinates divide by 1e6.
  - `ORDER BY`/`LIMIT` scans need nothing beyond what is listed above.
    The newest SQLite feature used is WITHOUT ROWID (3.8.2), so even Android
    8.0's SQLite 3.18 opens the packs. No FTS5 or JSON1 is needed.
- **Display:** show the match type so the driver knows how far to trust the
  pin. Exact is a house point. Interpolated is roughly ±50 m. Nearest-house
  and street can be hundreds of metres off, so suggest "adjust pin". The
  existing `AddressMemory` pin corrections should take priority over the
  pack.

## Known limitations

- **Freshness:** NAD freshness varies by county, so some counties are years
  old. New construction is missing until the county submits it to NAD, NAD
  publishes it and Overture re-releases. Rebuild the pack when Overture
  publishes a new release.
- **Units and apartments:** the unit is matched exactly after normalization.
  Without a typed unit you get the building point, or the centroid of its
  units. Multi-building complexes repeat the same unit labels, which is
  ambiguous in the source.
- **No PO boxes, POIs or business names.** This pack holds street addresses
  only. "Walmart Crossville" will not match.
- **Interpolation is approximate** (see the numbers above). It assumes
  numbers are spaced linearly along the street and that the street does not
  bend between the two neighbours. There is no centerline snapping yet.
- **No fuzzy matching or typo tolerance.** "Hickroy" will not find "Hickory".
  Only a half-typed last word is forgiven. Autocomplete on a half-typed
  *abbreviated* word also fails: "Old Hickory Boul" works, because `boul`
  maps to `blvd`, but "Old Hickory Bou" does not match `blvd`.
- **Route collisions:** US-N and TN-N share the `hwy N` norm (see the
  trade-off above). Interstates are kept separate, and NAD has few
  residential addresses on them.
- **Mile-marker addresses** ("244 MM I-40 W") are stored with unit `mm`, but
  the query parser does not recognise "MM" when it is typed.
- **Street names:** "MCDONALD" displays as "Mcdonald". The norm is
  unaffected.
