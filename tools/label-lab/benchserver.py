#!/usr/bin/env python3
"""Job server for the label bench app: the phone polls it over Wi-Fi, so no cable and no tapping.

    python3 tools/label-lab/benchserver.py [--port 8799] [--dir DIR]

GET  /job            -> 204 when idle, else {"id": ..., "zip": "/job/<id>.zip"} for the oldest queued job
GET  /job/<id>.zip   -> the job (pictures + options.json)
POST /result/<id>    -> the results zip (out.jsonl, done.txt, options.json); the job then leaves the queue
bench.py --net drops zips into DIR/queue and waits for DIR/results/<id>.zip. Plain http, home network only: it serves only label pictures.
"""
import argparse, http.server, json, os, tempfile

ap = argparse.ArgumentParser(); ap.add_argument("--port", type=int, default=8799)
ap.add_argument("--dir", default=os.path.join(tempfile.gettempdir(), "label-bench-net")); a = ap.parse_args()
Q, R = os.path.join(a.dir, "queue"), os.path.join(a.dir, "results")
os.makedirs(Q, exist_ok=True); os.makedirs(R, exist_ok=True)

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *x): pass
    def reply(self, code, body=b"", ctype="application/json"):
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        if self.path == "/job":
            jobs = sorted(f for f in os.listdir(Q) if f.endswith(".zip"))
            if not jobs: return self.reply(204)
            i = jobs[0][:-4]; return self.reply(200, json.dumps({"id": i, "zip": f"/job/{i}.zip"}).encode())
        if self.path.startswith("/job/") and self.path.endswith(".zip") and "/" not in self.path[5:]:
            p = os.path.join(Q, self.path[5:])
            if os.path.exists(p): return self.reply(200, open(p, "rb").read(), "application/zip")
        self.reply(404)
    def do_POST(self):
        if self.path.startswith("/result/") and "/" not in self.path[8:] and ".." not in self.path:
            i = self.path[8:]; body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            open(os.path.join(R, i + ".zip.part"), "wb").write(body); os.replace(os.path.join(R, i + ".zip.part"), os.path.join(R, i + ".zip"))
            try: os.remove(os.path.join(Q, i + ".zip"))
            except FileNotFoundError: pass
            return self.reply(200, b"{}")
        self.reply(404)

http.server.ThreadingHTTPServer(("0.0.0.0", a.port), H).serve_forever()
