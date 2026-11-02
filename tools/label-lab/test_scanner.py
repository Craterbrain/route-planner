#!/usr/bin/env python3
"""Runs rendered labels through the debug build's real flow on a USB-connected phone and scores it.

    python3 tools/label-lab/test_scanner.py [--passes 10] [--labels 25] [--warp W] [--dirt D] [--soft S] [--seed0 N] [--keep DIR]

For every label: render it (Firefox headless), push the picture to the phone, run text recognition + line ordering + label
parser + address lookup (the same code the scanner uses) and add the stop to the route. The route is cleared before each pass.
A label is correct when the first offered address has the right house number, street word and ZIP, and the stop lands
within 250 m of the true position.

Needs: label-lab server on :8765, the debug build (app.routeplanner.debug) installed, Firefox, adb (USB).
"""
import argparse, concurrent.futures as cf, json, math, os, re, subprocess, tempfile, time, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--passes", type=int, default=10); ap.add_argument("--labels", type=int, default=25)
ap.add_argument("--warp", default="1"); ap.add_argument("--dirt", default="1"); ap.add_argument("--soft", default="0.6")
ap.add_argument("--fade", default="0.8"); ap.add_argument("--streak", default="0.8"); ap.add_argument("--feed", default="0.8")
ap.add_argument("--seed0", type=int, default=1000); ap.add_argument("--keep")
a = ap.parse_args()

PKG = "app.routeplanner.debug"
work = a.keep or tempfile.mkdtemp(prefix="labels-")
os.makedirs(work, exist_ok=True)
sh = lambda *c, **k: subprocess.run(c, capture_output=True, text=True, **k)
DEV = f"/sdcard/Android/data/{PKG}/files"

def norm(s): return re.sub(r"[^A-Z0-9 ]", "", s.upper()).strip()
def meters(a_, b_, c_, d_):
    p = math.pi / 180
    x = (d_ - b_) * p * math.cos((a_ + c_) / 2 * p); y = (c_ - a_) * p
    return 6371000 * math.hypot(x, y)

def render(seed):
    png = os.path.join(work, f"label{seed}.png")
    prof = tempfile.mkdtemp()
    url = f"http://localhost:8765/?seed={seed}&shot&warp={a.warp}&dirt={a.dirt}&soft={a.soft}&fade={a.fade}&streak={a.streak}&feed={a.feed}"
    subprocess.run(["firefox", "--headless", "--no-remote", "--profile", prof, "--window-size", "800,1240", "--screenshot", png, url], capture_output=True, timeout=180)
    sh("rm", "-rf", prof)
    return png

def run_on_phone(seed, png, reset):
    name = f"label{seed}.png"
    sh("adb", "push", png, f"{DEV}/{name}")
    sh("adb", "shell", "rm", "-f", f"{DEV}/{name}.json")
    cmd = ["adb", "shell", "am", "broadcast", "-n", f"{PKG}/app.routeplanner.DebugOcr", "--es", "name", name]
    if reset: cmd += ["--ez", "reset", "true"]
    sh(*cmd)
    for _ in range(60):
        time.sleep(0.4)
        r = sh("adb", "shell", "cat", f"{DEV}/{name}.json")
        if r.stdout.strip().startswith("{"):
            return json.loads(r.stdout)
    return None

totals = []
all_fail = []
sh("adb", "shell", "am", "start", "-n", f"{PKG}/app.routeplanner.MainActivity")
time.sleep(3)
sh("adb", "shell", "rm", "-rf", DEV)                       # let the app (re)create its own folder, so it can read what is pushed
sh("adb", "shell", "am", "broadcast", "-n", f"{PKG}/app.routeplanner.DebugOcr", "--es", "name", "init")
time.sleep(1)
for p in range(a.passes):
    seeds = [a.seed0 + p * 1000 + n for n in range(a.labels)]
    t0 = time.time()
    with cf.ThreadPoolExecutor(8) as ex:
        pngs = list(ex.map(render, seeds))
    ok = ocr_ok = geo_ok = 0
    for n, (seed, png) in enumerate(zip(seeds, pngs)):
        truth = json.load(urllib.request.urlopen(f"http://localhost:8765/api/label?seed={seed}"))
        res = run_on_phone(seed, png, reset=(n == 0))     # route cleared at the start of the pass
        cand = (res or {}).get("candidates") or []
        got = norm(cand[0]["address"]) if cand else ""
        words = got.split()
        num, first = truth["street"].split()[0], norm(truth["street"]).split()[1] if len(truth["street"].split()) > 1 else ""
        text_ok = bool(cand) and num in words and first in words and truth["zip5"] in words
        g = (res or {}).get("geocoded") or {}
        dist = meters(truth["lat"], truth["lon"], g["lat"], g["lon"]) if g.get("lat") is not None else None
        good = text_ok and dist is not None and dist < 250
        ocr_ok += text_ok; geo_ok += (dist is not None and dist < 250); ok += good
        if not good:
            all_fail.append({"seed": seed, "want": f"{truth['street']} {truth['unit']}, {truth['city']} {truth['zip5']}", "got": cand[0]["address"] if cand else None,
                             "candidates": len(cand), "dist_m": None if dist is None else round(dist), "png": png})
        json.dump({"truth": truth, "result": res}, open(os.path.join(work, f"label{seed}.json"), "w"), indent=1)
    stops = (res or {}).get("routeStops")
    totals.append(ok)
    print(f"pass {p + 1}/{a.passes}: {ok}/{a.labels} fully correct (address read {ocr_ok}, located {geo_ok}); route has {stops} stops; {time.time() - t0:.0f}s", flush=True)
n_all = a.passes * a.labels
print(f"\nTOTAL {sum(totals)}/{n_all} ({100 * sum(totals) / n_all:.0f}%)  warp {a.warp} dirt {a.dirt} soft {a.soft} fade {a.fade} streak {a.streak} feed {a.feed}")
for f in all_fail[:40]: print("  FAIL", f)
json.dump(all_fail, open(os.path.join(work, "failures.json"), "w"), indent=1)
print("files in", work)
