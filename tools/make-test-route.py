#!/usr/bin/env python3
"""Make a test route (a CSV the app can import) from an address pack.

  tools/make-test-route.py [--pack data/cumberland/addresses.sqlite] [--stops 50] [--seed 1] [--out route.csv]

Picks random real addresses from the pack (one per house, no units), gives every stop 1-9 packages and every package
a fake tracking number (USPS, UPS or FedEx style; half of the USPS ones also get the longer "420"+ZIP+tracking form a
Code 128 barcode would hold). The output is a shared-route CSV (see RouteShare in core): open it on the phone with
Import spreadsheet, or share it to Route Planner. Pure stdlib; the tracking numbers are random and not real.
"""
import argparse
import csv
import random
import sqlite3
import sys

COLUMNS = ["address", "lat", "lng", "access_lat", "access_lng", "id", "package", "zone", "codes", "priority",
           "window_start", "window_end", "service_min", "notes", "approximate"]


def fake_tracking(rnd, zipcode):
    """(printed tracking number, [every code a label's barcodes might carry])."""
    kind = rnd.choice(["usps", "usps", "ups", "fedex"])
    if kind == "usps":
        t = "9400" + "".join(rnd.choice("0123456789") for _ in range(18))          # 22 digits, IMpb style
        codes = [t]
        if rnd.random() < 0.5:
            codes.append("420" + zipcode + t)                                       # what the Code 128 holds
        return t, codes
    if kind == "ups":
        alnum = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        t = "1Z" + "".join(rnd.choice(alnum) for _ in range(16))
        return t, [t]
    t = "".join(rnd.choice("0123456789") for _ in range(12))                        # FedEx Express
    return t, [t]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pack", default="data/cumberland/addresses.sqlite")
    ap.add_argument("--stops", type=int, default=50)
    ap.add_argument("--seed", type=int, default=None, help="repeatable output")
    ap.add_argument("--out", default="data/cumberland/test-route-50.csv")
    a = ap.parse_args()
    rnd = random.Random(a.seed)

    db = sqlite3.connect(f"file:{a.pack}?mode=ro", uri=True)
    rows = db.execute(
        "SELECT a.number, s.name, COALESCE(s.pcity, s.city), s.postcode, a.lat, a.lon "
        "FROM addr a JOIN streets s ON s.id = a.street_id "
        "WHERE a.unit = '' AND a.num > 0 AND s.postcode IS NOT NULL AND length(s.postcode) = 5").fetchall()
    if len(rows) < a.stops:
        sys.exit(f"the pack only has {len(rows)} usable addresses")
    chosen, seen = [], set()
    for number, street, city, zipcode, lat, lon in rnd.sample(rows, len(rows)):
        address = f"{number} {street}, {city or ''}, TN {zipcode}".replace(", ,", ",")
        if address.lower() in seen:
            continue
        seen.add(address.lower())
        chosen.append((address, lat / 1e6, lon / 1e6, zipcode))
        if len(chosen) == a.stops:
            break

    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(COLUMNS)
        packages = 0
        for i, (address, lat, lng, zipcode) in enumerate(chosen, start=1):
            n = rnd.randint(1, 9)
            for k in range(1, n + 1):
                _, codes = fake_tracking(rnd, zipcode)
                w.writerow([address, f"{lat:.6f}", f"{lng:.6f}", "", "", i, f"{i}-{k}", "", ";".join(codes), "", "", "", "", "", ""])
                packages += 1
    print(f"{len(chosen)} stops, {packages} packages -> {a.out}")


if __name__ == "__main__":
    main()
