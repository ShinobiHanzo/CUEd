# CUEd

An offline-first Android music player built around three things Spotify won't give you:
a **real crossfade** (two decks, tempo-matched), a **spectrograph you can scrub**, and
**sharing that never touches a server** (QR, NFC, local Wi-Fi, or a plain link that the
other phone re-downloads with `spotdl`).

No accounts. No telemetry. No models. Every recommendation is a rule you can read in
[`core/src/main/kotlin/dev/cued/core/reco`](core/src/main/kotlin/dev/cued/core/reco).

## What's in the box

| Area | What it does | Where |
|---|---|---|
| Playback | Two ExoPlayer decks blended by a `SimpleBasePlayer`; standard crossfade with equal-power / linear / smooth curves, 1–20 s, or gapless | `app/.../playback/CrossfadePlayer.kt` |
| Tempo-match (separate mode, off by default) | "Closest common factor": the incoming track is matched at 1:1, 2:1, 1:2, 3:2, 2:3, 4:3, 3:4, 3:1 or 1:3, whichever needs the least stretch; both decks glide to the incoming tempo; blend starts on a beat; falls back to the standard crossfade when tempos don't relate | `core/.../mix/TempoMatcher.kt`, `Crossfade.kt` |
| Silence skip | Optional: cuts quiet stretches longer than a tolerance (threshold in dBFS), position stays accurate because dropped frames are reported to the audio sink | `app/.../playback/SilenceSkipProcessor.kt` |
| Car mode | Sidebar toggle (or automatic on car UI mode): one screen of oversized controls, quick-play tiles, voice button, spoken replies. Android Auto browse tree via `MediaLibraryService` | `ui/screens/CarModeScreen.kt`, `playback/LibraryTree.kt` |
| Voice | In-app mic, Google Assistant and Android Auto ("play <x> on CUEd"). Rule-based command parser, no model | `core/.../voice/VoiceCommands.kt`, `app/.../playback/VoiceResolver.kt` |
| BPM detection | Onset-energy autocorrelation with a tempo prior, octave-error handling and beat-phase estimate. Runs on-device, one track at a time | `core/.../dsp/BpmDetector.kt` |
| Spectrograph | Live FFT tapped from ExoPlayer's audio pipeline (no mic permission) drawn as the scrubber, or a plain bar. Toggle on the Now Playing screen | `core/.../dsp/Spectrogram.kt`, `app/.../playback/SpectrumTapProcessor.kt`, `ui/components/PositionScrubber.kt` |
| Gestures | Artwork: swipe left/right for next/previous, swipe up for sing-along lyrics over the blurred art, tap play/pause, double-tap favourite, hold for the menu. Mini player: swipe to skip | `ui/screens/NowPlayingScreen.kt`, `ui/components/MiniPlayer.kt` |
| Podcasts & audiobooks | Anything over 12 minutes is sorted out of the music library into its own page (drawer): resumes where you stopped, ±10/30 s and speed controls, never crossfaded or recommended. Movable by hand from the track menu |
| Lyrics | Embedded ID3 lyrics → `.lrc`/`.txt` sidecar → lrclib.net (toggle). Synced LRC scrolls with the song and seeks on tap; bulk download for the library | `core/.../lyrics/`, `app/.../lyrics/`, `ui/components/LyricsPanel.kt` |
| Library | MediaStore scan into Room; favourites; free-text genre labels (many per track); play/skip history | `app/.../data` |
| Smart lists | Trending (recency-weighted plays), Newly downloaded, Unplayed, Forgotten, Favourites, Recommended (genre/artist/tempo/co-play heuristics, with reasons shown) | `core/.../reco/SmartLists.kt`, `Recommender.kt` |
| Playlists | Create, rename, reorder, save any smart list or genre or "more like this" as a playlist | `ui/screens/PlaylistsScreen.kt` |
| Downloads | `spotdl` via **Termux** on the phone, or via the **companion server** on a laptop/Pi over LAN. Spotify/YouTube links or search text; share-sheet integration | `app/.../download`, `tools/spotdl-server` |
| Sharing | QR code + NFC (host-card emulation, phone-to-phone, plus NDEF stickers) carrying a `cued://share?…` payload with the source link, a local file URL and an APK URL. Local HTTP server serves the track and the app itself | `app/.../share`, `core/.../share/SharePayload.kt` |

## Get the app

Download the latest APK from the **[Releases](https://github.com/ShinobiHanzo/CUEd/releases)** page
and sideload it. Pre-releases are signed with the public dev key described in
[`keystore/README.md`](keystore/README.md); only install builds from that page.

## Building

Requirements: JDK 17+, Android SDK (API 35), Gradle wrapper included.

```bash
./gradlew :core:test          # pure-JVM DSP / mixing / reco tests, no Android SDK needed
./gradlew :app:assembleDebug  # needs ANDROID_HOME
```

`:app` is skipped automatically when no SDK is configured, so `:core` builds anywhere.
CI (`.github/workflows/android.yml`) runs both and uploads a debug APK on every push.
Pushing a tag `vX.Y.Z` runs `.github/workflows/release.yml`, which builds a signed release
APK and publishes it on the Releases page.

## Docs

- [Architecture](docs/architecture.md): modules, threading, how the two-deck engine reports one `Player`
- [Tempo-matched crossfade](docs/tempo-match.md): the maths, the knobs, the limits
- [Downloading with spotdl](docs/downloading.md): Termux vs companion setup
- [Sharing](docs/sharing.md): QR / NFC / local server protocol and what leaves the phone (nothing)
- [Car mode and voice](docs/car-and-voice.md): sidebar car mode, Android Auto, what you can say
- [Lyrics](docs/lyrics.md): where lyrics come from and what goes online

## Status

First cut. The core library is unit-tested; the Android module is built in CI but has not
yet been run on a device. Expect rough edges around Termux permissions and NFC on
specific OEM firmware. Issues and PRs welcome; keep it offline-first.

## License

MIT. See [LICENSE](LICENSE).
