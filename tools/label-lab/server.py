#!/usr/bin/env python3
"""Label lab: serves test shipping labels for the scanner, built from the Cumberland County address pack.

    python3 tools/label-lab/server.py [port]        (default 8765; needs `npm install` here for the barcode library)

Every label is a SAMPLE for scanner testing: generic layout, made-up recipient names and tracking numbers, a fictional
return address, no carrier or retailer logos, and a "TEST LABEL" footer.
"""
import json, os, random, re, sqlite3, sys, time
from urllib.parse import parse_qs, urlparse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(HERE, "..", "..", "data", "cumberland", "addresses.sqlite")

FIRST = """James Mary John Patricia Robert Jennifer Michael Linda William Elizabeth David Barbara Richard Susan Joseph Jessica
Thomas Sarah Charles Karen Christopher Lisa Daniel Nancy Matthew Betty Anthony Sandra Mark Ashley Donald Emily Steven Kimberly
Paul Donna Andrew Michelle Joshua Carol Kenneth Amanda Kevin Melissa Brian Deborah George Stephanie Timothy Rebecca Ronald Laura
Edward Sharon Jason Cynthia Jeffrey Kathleen Ryan Amy Jacob Angela Gary Shirley Nicholas Anna Eric Brenda Jonathan Pamela""".split()
LAST = """Smith Johnson Williams Brown Jones Garcia Miller Davis Rodriguez Martinez Hernandez Lopez Gonzalez Wilson Anderson Thomas
Taylor Moore Jackson Martin Lee Perez Thompson White Harris Sanchez Clark Ramirez Lewis Robinson Walker Young Allen King
Wright Scott Torres Nguyen Hill Flores Green Adams Nelson Baker Hall Rivera Campbell Mitchell Carter Roberts Gomez Phillips
Evans Turner Diaz Parker Cruz Edwards Collins Reyes Stewart Morris Morales Murphy Cook Rogers Gutierrez Ortiz Morgan Cooper""".split()
SUFFIX = {"Street": "ST", "Road": "RD", "Avenue": "AVE", "Drive": "DR", "Lane": "LN", "Court": "CT", "Circle": "CIR",
          "Boulevard": "BLVD", "Trail": "TRL", "Place": "PL", "Parkway": "PKWY", "Way": "WAY", "Highway": "HWY"}
DIRS = {"North": "N", "South": "S", "East": "E", "West": "W"}

db = sqlite3.connect(f"file:{DB}?mode=ro", uri=True, check_same_thread=False)
STREETS = db.execute("""SELECT s.id, s.name, s.city, s.postcode FROM streets s
                        WHERE s.postcode GLOB '[0-9][0-9][0-9][0-9][0-9]' AND s.city IS NOT NULL
                          AND EXISTS (SELECT 1 FROM addr a WHERE a.street_id = s.id AND a.unit = '' AND a.num > 0)""").fetchall()


def labelize(street):
    """Street name as printed on a label: upper case, usually abbreviated the way mail is, sometimes spelled out."""
    words = street.upper().split()
    out = []
    for i, w in enumerate(words):
        title = w.title()
        if i == len(words) - 1 and title in SUFFIX and random.random() < 0.75:
            out.append(SUFFIX[title])
        elif title in DIRS and random.random() < 0.7:
            out.append(DIRS[title])
        else:
            out.append(w)
    return " ".join(out)


def tracking():
    """22-digit USPS-style number (93612897...), grouped 4-4-4-4-4-2, with a mod-10 check digit."""
    body = "9361" + "".join(random.choice("0123456789") for _ in range(17))
    total = 0
    for i, ch in enumerate(reversed(body)):
        total += int(ch) * (3 if i % 2 == 0 else 1)
    return body + str((10 - total % 10) % 10)


def make_label():
    sid, name, city, zip5 = random.choice(STREETS)
    num, number, lat, lon = random.choice(db.execute("SELECT num, number, lat, lon FROM addr WHERE street_id=? AND unit='' AND num>0", (sid,)).fetchall())
    unit = random.choice(["", "", "", "", "", f"APT {random.randint(1, 40)}", f"LOT {random.randint(1, 99)}", f"STE {random.randint(1, 20)}"])
    t = tracking()
    zip4 = f"{random.randint(1, 9999):04d}"
    return {
        "name": f"{random.choice(FIRST)} {random.choice(LAST)}".upper(),
        "street": f"{number} {labelize(name)}",
        "unit": unit,
        "city": city.upper(), "state": "TN", "zip5": zip5, "zip4": zip4,
        "lat": lat / 1e6, "lon": lon / 1e6,
        "tracking": t,
        "routeCode": f"R{random.randint(1, 99):03d}",
        "sortCodes": random.sample(["FSD1", "MDW5", "BNA7", "TYS5", "CVG2", "IND9", "HOU3", "SDF8", "ATL6", "MEM1"], 4),
        "seq": f"{random.choice('DB')} {random.randint(1, 99):03d}",
        "serial": "".join(random.choice("0123456789") for _ in range(12)),
        "weight": f"{random.randint(1, 30)} LB",
        "returnLines": ["SAMPLE FULFILLMENT CO", f"{random.randint(100, 9999)} SAMPLE WAY", "NASHVILLE TN 37201"],
        "returnCode": random.choice(["FSD1", "MDW5", "BNA7", "CVG2"]),
    }


SERVED = {}
ADVANCE = {"n": 0}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=HERE, **k)

    def do_GET(self):
        q = parse_qs(urlparse(self.path).query)
        if "seed" in q:
            random.seed(int(q["seed"][0]))  # ?seed=N makes the label reproducible (for the test harness)
        if self.path.startswith("/api/advance"):      # the page polls /api/cmd and shows a new label when this counter moves
            ADVANCE["n"] += 1
        if self.path.startswith("/api/cmd") or self.path.startswith("/api/advance"):
            body = json.dumps(ADVANCE).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
            return
        if self.path.startswith("/api/last"):
            body = json.dumps(SERVED).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
            return
        if self.path.startswith("/api/label"):
            label = make_label()
            if "seed" not in q: SERVED.clear(); SERVED.update(label, at=time.time())
            body = json.dumps(label).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.split("?")[0] in ("/", "/index.html"):
            # The first label rides along in the page, so it is drawn before anything is fetched.
            page = open(os.path.join(HERE, "index.html"), encoding="utf-8").read()
            first = make_label()
            if "seed" not in q: SERVED.clear(); SERVED.update(first, at=time.time())
            page = page.replace("<script src=\"app.js\">", "<script>window.__first = " + json.dumps(first) + ";</script>\n<script src=\"app.js\">")
            body = page.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        return super().do_GET()

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"label lab on http://localhost:{port}  ({len(STREETS)} streets in the Cumberland County pack)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
