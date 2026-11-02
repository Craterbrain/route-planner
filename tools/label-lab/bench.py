#!/usr/bin/env python3
"""Runs many rendered labels through the label bench app on the phone in one go and scores what it read.

    python3 tools/label-lab/bench.py [--labels 250] [--seed0 5000] [--fade F --streak S --feed D --warp W --dirt X --soft Y]
                                     [--region 0|1] [--save-failures TAG] [--cache DIR]

Steps: render each label once (Firefox headless, 8 at a time, cached by settings and seed) -> push the whole folder to the phone ->
the bench app (app.routeplanner.labelbench) runs ML Kit text + barcode reading, the bright-label finder, line ordering and the
label parser over every picture -> pull its results -> score against the label's true address (from the label server).
--save-failures TAG writes each failure as a fixture in core/src/test/resources/labelcorpus/ (the recognizer's own boxes and
text plus the true address); `./gradlew :core:test` then replays them on this PC, so a parser fix can be developed without the phone.

Needs: the label server on :8765 (python3 tools/label-lab/server.py), the bench app installed, Firefox, adb.
"""
import argparse, concurrent.futures as cf, hashlib, json, os, re, subprocess, sys, tempfile, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ap = argparse.ArgumentParser()
ap.add_argument("--labels", type=int, default=250); ap.add_argument("--seed0", type=int, default=5000)
for k, d in [("warp", "0.6"), ("dirt", "0.8"), ("soft", "0.6"), ("fade", "0.8"), ("streak", "0.8"), ("feed", "0.8")]: ap.add_argument(f"--{k}", default=d)
ap.add_argument("--region", default="1"); ap.add_argument("--threads", default="3")
ap.add_argument("--save-failures", metavar="TAG"); ap.add_argument("--cache", default=os.path.join(tempfile.gettempdir(), "label-bench-cache"))
a = ap.parse_args()

PKG = "app.routeplanner.labelbench"
DEV = f"/sdcard/Android/data/{PKG}/files"
sh = lambda *c, **k: subprocess.run(c, capture_output=True, text=True, **k)
params = f"warp={a.warp}&dirt={a.dirt}&soft={a.soft}&fade={a.fade}&streak={a.streak}&feed={a.feed}"
folder = os.path.join(a.cache, hashlib.md5(params.encode()).hexdigest()[:10]); os.makedirs(folder, exist_ok=True)
seeds = [a.seed0 + n for n in range(a.labels)]

def render(seed):
    png = os.path.join(folder, f"label{seed}.png")
    if os.path.exists(png): return
    prof = tempfile.mkdtemp()
    subprocess.run(["firefox", "--headless", "--no-remote", "--profile", prof, "--window-size", "800,1240", "--screenshot", png,
                    f"http://localhost:8765/?seed={seed}&shot&{params}"], capture_output=True, timeout=180)
    sh("rm", "-rf", prof)

t0 = time.time()
need = [s for s in seeds if not os.path.exists(os.path.join(folder, f"label{s}.png"))]
with cf.ThreadPoolExecutor(8) as ex: list(ex.map(render, seeds))
t_render = time.time() - t0

# the app creates (and so owns) its folder on first start
sh("adb", "shell", "am", "start", "-n", f"{PKG}/.BenchActivity", "--es", "dir", "none"); time.sleep(2)
sh("adb", "shell", "rm", "-rf", f"{DEV}/in"); sh("adb", "shell", "mkdir", "-p", f"{DEV}/in")
wanted = {f"label{s}.png" for s in seeds}
t1 = time.time()
for f in sorted(wanted): pass
subprocess.run(["adb", "push", "--sync", folder + "/.", f"{DEV}/in/"], capture_output=True)
sh("adb", "shell", "sh", "-c", f"cd {DEV}/in && ls | grep -v -x -E '{'|'.join(sorted(wanted))}' | xargs rm -f")   # only this run's labels
t_push = time.time() - t1
sh("adb", "shell", "rm", "-f", f"{DEV}/done.txt", f"{DEV}/out.jsonl")
t2 = time.time()
sh("adb", "shell", "am", "start", "-n", f"{PKG}/.BenchActivity", "--es", "dir", "in", "--es", "region", a.region, "--es", "threads", a.threads)
done = ""
while time.time() - t2 < 1200:
    time.sleep(1)
    done = sh("adb", "shell", "cat", f"{DEV}/done.txt").stdout.strip()
    if done and "No such file" not in done: break
t_ocr = time.time() - t2
out = os.path.join(folder, "out.jsonl")
sh("adb", "pull", f"{DEV}/out.jsonl", out)

import difflib
norm = lambda s: re.sub(r"[^A-Z0-9 ]", "", s.upper()).strip()
rows = {}
for line in open(out):
    r = json.loads(line); rows[r["file"]] = r
exact = near = 0; fails = []; ms = []
cats = {"no street line read": 0, "return address offered": 0, "wrong or garbled": 0}
for s in seeds:
    r = rows.get(f"label{s}.png")
    truth = json.load(urllib.request.urlopen(f"http://localhost:8765/api/label?seed={s}"))
    cand = (r or {}).get("candidates") or []
    if r: ms.append(r.get("ms", 0))
    parts = norm(truth["street"]).split()
    kept = " ".join(norm(k["t"]) for k in (r or {}).get("kept", []))
    if cand and cand[0]["role"] != "RETURN":
        got = norm(cand[0]["address"]); words = got.split()
        if parts[0] in words and parts[1] in words and truth["zip5"] in words: exact += 1; continue
        # near: right house number, street word within two letters, right city: the geocoder's fuzzy search would resolve it
        if parts[0] in words and any(difflib.SequenceMatcher(None, parts[1], w).ratio() >= 0.7 for w in words) and norm(truth["city"].split()[0])[:4] in got:
            near += 1; continue
    why = "wrong or garbled"
    if not cand or cand[0]["role"] == "RETURN":
        has_city = norm(truth["city"]) in kept or truth["zip5"] in kept
        why = "no street line read" if (parts[0] not in kept.split() and has_city) else "return address offered"
    cats[why] += 1; fails.append((s, truth, r, why))
n = len(seeds)
print(f"exact {exact} + near {near} = {exact + near}/{n} usable ({100 * (exact + near) // n}%), exact {100 * exact // n}%   "
      f"render {t_render:.0f}s ({len(need)} new), push {t_push:.0f}s, phone {t_ocr:.0f}s = {1000 * t_ocr / max(n, 1):.0f} ms/label; ML Kit median {sorted(ms)[len(ms) // 2] if ms else 0} ms")
print(f"settings: {params} region={a.region}")
print("misses:", ", ".join(f"{k} {v}" for k, v in cats.items() if v))
for s, truth, r, why in fails[:30]:
    c = ((r or {}).get("candidates") or [{}])[0]
    print(f"  MISS {s} [{why}]: want {truth['street']} {truth['unit']}, {truth['city']} {truth['zip5']}   got {c.get('address')} ({c.get('role')})")
if a.save_failures:
    d = os.path.join(HERE, "..", "..", "core", "src", "test", "resources", "labelcorpus"); os.makedirs(d, exist_ok=True)
    for s, truth, r, why in fails:
        if not r or "kept" not in r or why == "no street line read": continue   # nothing to parse: not a parser fixture
        json.dump({"tag": a.save_failures, "seed": s, "settings": params,
                   "truth": {k: truth[k] for k in ("name", "street", "unit", "city", "state", "zip5", "zip4")},
                   "lines": r["kept"]}, open(os.path.join(d, f"{a.save_failures}-{s}.json"), "w"), indent=1)
    print(f"saved failure fixtures to core/src/test/resources/labelcorpus/")
