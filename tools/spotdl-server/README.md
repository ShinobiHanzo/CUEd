# CUEd companion download server

Runs `spotdl` on a computer so the phone does not have to. Zero dependencies
beyond spotdl itself (and ffmpeg on the PATH).

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python3 server.py --port 8766 --dir ~/Music/cued-inbox
```

Then in CUEd: Downloads → Backend → Companion (LAN) → enter `http://<pc-ip>:8766` → Test.

Only ever bind this to a trusted LAN; there is deliberately no auth so it stays a
150-line script you can read in full. For remote use, tunnel it (Tailscale, WireGuard, SSH).
