# Downloading with spotdl

`spotdl` is Python; Android won't run it natively. CUEd supports two ways, both offline
from CUEd's point of view (the app itself makes no network requests except to the LAN
companion or its own share server).

## Option A: Termux on the phone

1. Install Termux from F-Droid (the Play Store build is abandoned).
2. In Termux:
   ```bash
   pkg install python ffmpeg
   pip install spotdl
   termux-setup-storage
   mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
   ```
   Then restart Termux.
3. In Android settings, grant CUEd the **"Run commands in Termux"** permission
   (Apps → CUEd → Permissions, or Apps → Termux → the additional-permissions list).
4. CUEd → Downloads → Backend → **Termux**. Paste a link, Queue.

CUEd sends `spotdl download <link> --output /sdcard/Music/CUEd/{artists} - {title}.{output-ext}`
via Termux's `RUN_COMMAND` intent and gets the exit code back through a pending intent.
Files land in `Music/CUEd` and the library rescans.

## Option B: companion server on a computer or Pi

```bash
cd tools/spotdl-server
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt   # spotdl; also install ffmpeg via your package manager
python3 server.py --port 8766
```

CUEd → Downloads → Backend → **Companion (LAN)** → enter `http://<that-machine-ip>:8766`
→ Test. Downloads run on the computer and the finished files are pulled over to
`Music/CUEd` by the phone. Good for weak phones and for bulk playlist pulls.

The server is ~150 lines, has no auth and is meant for a trusted LAN. Tunnel it
(Tailscale, WireGuard, SSH) rather than exposing it.

## Share-sheet

Share a Spotify/YouTube link from any app to CUEd and it lands in the Downloads box.

## Source links

When a track was downloaded through CUEd its source URL is stored, so sharing it as a
link lets the other phone re-download rather than copy the file. For files that arrived
some other way you can set the source link from the track menu.
