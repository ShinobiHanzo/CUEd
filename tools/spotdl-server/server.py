#!/usr/bin/env python3
"""
CUEd companion download server.

A ~150-line wrapper around `spotdl` so a phone on the same Wi-Fi can ask a
laptop / Raspberry Pi / old PC to do the downloading. No accounts, no
cloud, only the LAN. Run it, point the app's Downloads screen at
http://<this-machine-ip>:8766 and queue links.

Usage:
    pip install spotdl            # plus ffmpeg on your PATH
    python3 server.py [--port 8766] [--dir ./downloads] [--bind 0.0.0.0]

API (JSON):
    GET  /health               -> "ok"
    POST /jobs {url, format, lrc}   -> {"id": "..."}   (lrc: also write .lrc lyrics files)
    GET  /jobs/<id>            -> {"status": "queued|running|done|failed", "progress": 0..1, "message": "...", "files": [...]}
    GET  /files/<name>         -> the audio file

Security model: this binds to your LAN and has no auth. It only serves files
it downloaded itself, and only accepts URLs (or search strings) for spotdl.
Don't expose it to the internet; put it behind Tailscale/WireGuard if you
want it from outside the house.
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

JOBS = {}
LOCK = threading.Lock()
QUEUE = []
DOWNLOAD_DIR = os.path.abspath("downloads")
AUDIO_EXT = {".mp3", ".m4a", ".opus", ".ogg", ".flac", ".wav"}
TEXT_EXT = {".lrc"}
SERVE_EXT = AUDIO_EXT | TEXT_EXT


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def snapshot(directory):
    return {f for f in os.listdir(directory) if os.path.splitext(f)[1].lower() in SERVE_EXT}


def run_job(job_id):
    job = JOBS[job_id]
    job["status"] = "running"
    job["progress"] = 0.05
    before = snapshot(DOWNLOAD_DIR)
    cmd = [
        sys.executable, "-m", "spotdl", "download", job["url"],
        "--output", os.path.join(DOWNLOAD_DIR, "{artists} - {title}.{output-ext}"),
        "--format", job.get("format", "mp3"),
        "--overwrite", "skip",
        "--simple-tui",
    ]
    if job.get("lrc"):
        cmd.append("--generate-lrc")
    log("running:", " ".join(cmd))
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        last = ""
        for line in proc.stdout:
            line = line.strip()
            if line:
                last = line[-200:]
                job["message"] = last
                # spotdl prints a percentage in its simple tui; nudge progress when we see one.
                for tok in line.replace("%", " % ").split():
                    if tok.isdigit() and 0 < int(tok) <= 100:
                        job["progress"] = max(job["progress"], 0.05 + 0.85 * int(tok) / 100)
                        break
        code = proc.wait()
        after = snapshot(DOWNLOAD_DIR)
        new_files = sorted(after - before)
        if code != 0 and not new_files:
            job["status"] = "failed"
            job["message"] = last or f"spotdl exited with {code}"
        else:
            job["status"] = "done"
            job["progress"] = 1.0
            job["files"] = new_files
            job["message"] = f"{len(new_files)} file(s)" if new_files else "nothing new (already downloaded?)"
    except Exception as e:  # noqa: BLE001
        job["status"] = "failed"
        job["message"] = str(e)
    log("job", job_id, job["status"], job["message"])


def worker():
    while True:
        with LOCK:
            job_id = QUEUE.pop(0) if QUEUE else None
        if job_id is None:
            time.sleep(0.5)
            continue
        run_job(job_id)


class Handler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        path = unquote(self.path)
        if path == "/health":
            self._json(200, "ok")
        elif path.startswith("/jobs/"):
            job = JOBS.get(path[len("/jobs/"):])
            if not job:
                return self._json(404, {"error": "no such job"})
            self._json(200, {k: job[k] for k in ("status", "progress", "message", "files")})
        elif path.startswith("/files/"):
            name = os.path.basename(path[len("/files/"):])
            full = os.path.join(DOWNLOAD_DIR, name)
            if not os.path.isfile(full) or os.path.splitext(name)[1].lower() not in SERVE_EXT:
                return self._json(404, {"error": "no such file"})
            mime = {".m4a": "audio/mp4", ".opus": "audio/ogg", ".ogg": "audio/ogg", ".flac": "audio/flac", ".wav": "audio/wav", ".lrc": "text/plain; charset=utf-8"}.get(os.path.splitext(name)[1].lower(), "audio/mpeg")
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(os.path.getsize(full)))
            self.end_headers()
            with open(full, "rb") as f:
                while chunk := f.read(1 << 16):
                    self.wfile.write(chunk)
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        if self.path != "/jobs":
            return self._json(404, {"error": "not found"})
        length = int(self.headers.get("Content-Length", "0"))
        try:
            req = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            return self._json(400, {"error": "bad json"})
        url = str(req.get("url", "")).strip()
        if not url or url.startswith("-"):
            return self._json(400, {"error": "missing url"})
        fmt = str(req.get("format", "mp3"))
        if fmt not in {"mp3", "m4a", "opus", "flac", "ogg", "wav"}:
            fmt = "mp3"
        job_id = uuid.uuid4().hex[:12]
        JOBS[job_id] = {"url": url, "format": fmt, "lrc": bool(req.get("lrc", False)), "status": "queued", "progress": 0.0, "message": "queued", "files": []}
        with LOCK:
            QUEUE.append(job_id)
        log("queued", job_id, url)
        self._json(200, {"id": job_id})

    def log_message(self, fmt, *args):  # quieter default logging
        pass


def main():
    global DOWNLOAD_DIR
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8766)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--dir", default="downloads")
    args = ap.parse_args()
    DOWNLOAD_DIR = os.path.abspath(args.dir)
    os.makedirs(DOWNLOAD_DIR, exist_ok=True)
    threading.Thread(target=worker, daemon=True).start()
    srv = ThreadingHTTPServer((args.bind, args.port), Handler)
    log(f"CUEd companion listening on http://{args.bind}:{args.port}  (downloads -> {DOWNLOAD_DIR})")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
