# Downloading with spotdl

Three backends, pick one under Downloads → Backend:

## Built-in (default, no setup)

spotdl's pipeline rebuilt in Kotlin inside the app, no Python:

1. **Metadata.** Spotify links are read through Spotify's public embed page (title,
   artists, duration, cover, album/playlist track lists) with no keys. Add your own free
   Spotify developer client id/secret (developer.spotify.com → Create app) and CUEd uses
   the Web API instead: complete long playlists, ISRC, release year and artist genres
   written straight into the tag.
2. **Match.** YouTube Music search via NewPipeExtractor (the library behind the NewPipe
   app; no API key). The best result is picked by title words, artist presence and
   duration, penalising live/remix/cover uploads unless you asked for them.
3. **Download.** The AAC audio stream as `.m4a`, in ranged chunks. No transcoding, so no
   ffmpeg; the quality is what YouTube Music serves (~128 kbps AAC).
4. **Tag.** Title, artists, album, track number, year, genre, cover art, and the source
   link in the comment, written with jaudiotagger. The library scan then picks the file
   up, genres included.

Output: **m4a** (the AAC stream untouched, fastest, best quality) or **mp3** (decoded and re-encoded on the phone at 192 kbps with a pure-Java LAME; slower, and a generation lossier). Pick under Downloads → Format.

Limits, honestly: YouTube changes its site every few months and the
extractor has to catch up (update CUEd when downloads start failing with "no stream");
the keyless Spotify path depends on the embed page's layout and may truncate very long
playlists, which is what the API keys are for.

## Termux and companion (spotdl proper)

`spotdl` is Python; Android won't run it natively. These two backends run it elsewhere.

**They are developer-mode features.** Settings → **Developer mode** is off by default; while it
is off the Downloads screen shows only the built-in downloader, the backend chooser and the
Termux/companion test and repair tools are hidden, and every job runs built-in even if Termux or
the companion had been chosen earlier. Switch it on to get the chooser back; the previous choice
is remembered. Developer mode also unlocks **Theme colours** under Settings: the nine colours the
app is drawn with, with the desktop's presets; they apply only while developer mode is on.

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

CUEd runs spotdl through a Termux login shell (`bash -l -c …`) with Termux's `PATH` and
`LD_LIBRARY_PATH` pinned, via the `RUN_COMMAND` intent, and gets the exit code and output
back through a pending intent. Files land in `Music/CUEd` and the library rescans.
When something fails, turn on Settings → Debug log, reproduce, then Share log: it holds
the resolved link, every search result and score, the chosen stream, and Termux's full
stdout/stderr. **Test built-in downloader** and **Test spotdl in Termux** on the Downloads
screen run quick checks (python, spotdl,
ffmpeg, storage) and shows the result, so a broken install is visible before queueing.

If a job fails with `libpython… not found`, that was the old direct launch without the
Termux environment; update CUEd. If `spotdl: command not found`, run `pip install spotdl`
inside Termux again (a Termux update can reset Python).

## Option B: companion server on a computer or Pi (Windows, Linux, macOS)

Windows: install Python 3.9+, double-click `tools/spotdl-server/run.bat`.
Linux/macOS: `tools/spotdl-server/run.sh`. First run creates a venv and installs spotdl;
if ffmpeg isn't on the PATH, spotdl fetches its own copy. The console prints the exact
`http://ip:8766` to enter in the app.

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

## When YouTube says "Sign in to confirm you're not a bot"

That message comes from YouTube's web client. CUEd's built-in downloader uses
NewPipeExtractor, which (from v0.26) takes streams from YouTube's Android and
Vision OS clients instead, so updating CUEd is the first fix. If it still happens,
it is the network: try mobile data instead of Wi-Fi (or the other way round), as
YouTube challenges some IP ranges. The error in the app says which it is.

## Termux: "libpython3.13.so not found"

Termux upgraded its Python (3.13 → 3.14) after spotdl was installed, so pip
packages with native code still point at the old libpython. Downloads →
**Repair spotdl** reinstalls spotdl for the current Python (it runs
`pip install --force-reinstall spotdl` through Termux and shows the tail of the
output). If the repair itself fails on `curl_cffi`, run in Termux:

```
pkg install -y libcurl clang rust
pip install --force-reinstall --no-cache-dir spotdl
```

## m4a files and tags

YouTube serves its AAC audio as a fragmented MP4 (DASH). Tag libraries that rewrite
such a file in place corrupt it, which is what "the retagged copy isn't readable" meant.
CUEd now remuxes the stream into a plain MP4 with the platform muxer (sample copy, no
re-encode) at download time, and again on demand when Track details writes to an older
file, then writes the `ilst` metadata itself (`core/tag/Mp4Tags`, unit-tested). mp3
tags come from `core/tag/Id3v2`. There is no third-party tag library in the app any
more. Nothing is imported into the library until the platform extractor confirms it
plays, and a queue stops after five unplayable items in a row instead of cycling.
