# CUEd companion download server

Runs `spotdl` on a computer so the phone does not have to. Plain Python, no framework.

## Windows
1. Install Python 3.9+ from python.org (tick "Add python.exe to PATH").
2. Double-click `run.bat`. First run creates a virtual environment and installs spotdl;
   if ffmpeg isn't installed, spotdl downloads its own copy.
3. The window prints `enter this in CUEd: http://192.168.x.x:8766`. Put that under
   Downloads → Backend → Companion (LAN) → Test.
4. If the phone can't reach it, allow Python through Windows Firewall (private networks).

## Linux / macOS
```bash
./run.sh            # first run: venv + pip install; then starts on port 8766
./run.sh --port 9000 --dir ~/Music/inbox
```

## What it exposes
- `GET /` status page with the addresses to enter in the app
- `POST /jobs {url, format, lrc}` → `{id}`
- `GET /jobs/<id>` → status/progress/files
- `GET /files/<name>` → audio (and `.lrc` when requested)

Only ever bind this to a trusted LAN; there is deliberately no auth so it stays a small
script you can read in full. For remote use, tunnel it (Tailscale, WireGuard, SSH).
