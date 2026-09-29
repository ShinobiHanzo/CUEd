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

## Sharing links from any platform

Share a link to CUEd from any music app, or open a link with CUEd, and it lands in the
Downloads box with what was detected ("Apple Music album", "Deezer playlist"…). Share
text that only says "Song by Artist" works too: it becomes a search.

spotdl itself only reads Spotify and YouTube links, so other platforms are resolved
first, when the job runs:

| Shared | What happens |
|---|---|
| Spotify / YouTube / YouTube Music track, album, playlist | Straight to spotdl |
| Apple Music, Tidal, SoundCloud, Amazon, Deezer **track or album** | song.link (Odesli, open, no key) finds the matching Spotify link; if none, the title and artist become a search |
| **Deezer** playlist or album | Expanded track by track through Deezer's public API, one job per track |
| Apple Music, Tidal, SoundCloud, Amazon **playlist** | Not readable without an account: the job fails with a clear message. Share a Spotify/YouTube/Deezer playlist, or the tracks one by one |
| Bandcamp or anything else | The page's title becomes a search |

## Lyrics with downloads

Two checkboxes on the Downloads screen:

- **Save .lrc lyrics files next to tracks**: passes `--generate-lrc` to spotdl. With the
  companion, the `.lrc` comes back with the audio and is imported into CUEd. With
  Termux, it sits next to the file for other players; CUEd can read it there on
  Android 10 and older (newer Android hides non-media files from other apps).
- **Look lyrics up in CUEd after download** (default on): embedded tag first, then
  lrclib.net if allowed in Settings → Lyrics.

spotdl also embeds lyrics into the file's tag when it finds them, and CUEd reads those
on every Android version, so most downloads carry their words regardless.

## Source links

When a track was downloaded through CUEd its source URL is stored, so sharing it as a
link lets the other phone re-download rather than copy the file. For files that arrived
some other way you can set the source link from the track menu.
