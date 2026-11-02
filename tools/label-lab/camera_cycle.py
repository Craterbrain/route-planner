#!/usr/bin/env python3
"""Cycles labels on the monitor and scans each with the phone's camera (USB adb), logging what the scanner offered.

    python3 tools/label-lab/camera_cycle.py [--passes 10] [--labels 25]

Open  http://localhost:8765/?shot&fit&remote&warp=0.6&dirt=0.8  in Firefox first, full screen, with the phone pointed at the monitor.
The route is cleared before each pass. Counts as correct when the first address offered has the right number, street word and ZIP.
"""
import argparse, json, re, subprocess, time, urllib.request
ap = argparse.ArgumentParser(); ap.add_argument("--passes", type=int, default=10); ap.add_argument("--labels", type=int, default=25)
ap.add_argument("--wait", type=float, default=6.0)
a = ap.parse_args()
PKG = "app.routeplanner.debug"
sh = lambda *c: subprocess.run(c, capture_output=True, text=True).stdout
get = lambda p: json.load(urllib.request.urlopen("http://localhost:8765" + p))
norm = lambda s: re.sub(r"[^A-Z0-9 ]", "", s.upper()).strip()
tap = lambda x, y: sh("adb", "shell", "input", "tap", str(x), str(y))
def ui_text():
    sh("adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh("adb", "shell", "cat", "/sdcard/ui.xml")
def find(xml, pattern):
    m = re.search(r'text="(%s[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % pattern, xml)
    return (m.group(1), (int(m.group(2)) + int(m.group(4))) // 2, (int(m.group(3)) + int(m.group(5))) // 2) if m else None

total = good = 0; fails = []
for p in range(a.passes):
    sh("adb", "shell", "am", "broadcast", "-n", f"{PKG}/app.routeplanner.DebugOcr", "--es", "name", "init", "--ez", "reset", "true"); time.sleep(1.5)
    sh("adb", "shell", "am", "start", "-n", f"{PKG}/app.routeplanner.MainActivity"); time.sleep(2)
    ok = 0
    for n in range(a.labels):
        sh("adb", "shell", "am", "start", "-n", f"{PKG}/app.routeplanner.MainActivity"); time.sleep(0.8)
        prev = get("/api/last").get("at", 0)
        get("/api/advance")
        for _ in range(40):                       # wait until the page has really switched to a new label
            time.sleep(0.25)
            truth = get("/api/last")
            if truth.get("at", 0) != prev: break
        time.sleep(0.9)                           # and has finished drawing it
        tap(692, 196)                                   # the camera button: opens the scanner
        sh("adb", "logcat", "-c")
        t0 = time.time(); prim = []
        while time.time() - t0 < a.wait:                  # stop as soon as the scanner has offered an address
            time.sleep(0.3)
            log = sh("adb", "logcat", "-d", "-s", "RPDebug")
            prim = [l.split("cam primary: ", 1)[1] for l in log.splitlines() if "cam primary:" in l]
            if any(not p.startswith("null") for p in prim): break
        got = prim[-1] if prim else None
        words = norm(got or "").split()
        parts = norm(truth["street"]).split()
        right = bool(got) and not got.startswith("None") and parts[0] in words and parts[1] in words and truth["zip5"] in words
        # accept what was offered (as a courier would) or close the scanner if nothing was found
        xml = ui_text(); btn = find(xml, "Add: ")
        if btn: tap(btn[1], btn[2])
        else: sh("adb", "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.4)
        total += 1; good += right; ok += right
        if not right:
            rec = {"pass": p + 1, "n": n, "want": f"{truth['name']} | {truth['street']} {truth['unit']}, {truth['city']} {truth['zip5']}-{truth['zip4']}", "got": got}
            # the last frame's lines as the scanner saw them
            frame = []
            for l in reversed(log.splitlines()):
                if "cam line" in l: frame.append(l.split("RPDebug : ", 1)[-1])
                elif frame: break
            rec["lines"] = list(reversed(frame)); rec["region"] = [l.split("RPDebug : ", 1)[-1] for l in log.splitlines() if "cam region" in l][-1:]
            fails.append(rec); print("FAIL", json.dumps(rec), flush=True)
    print(f"pass {p + 1}/{a.passes}: {ok}/{a.labels}", flush=True)
print(f"\nTOTAL {good}/{total} ({100 * good // max(total, 1)}%)")
for f in fails[:30]: print("  FAIL", f)
