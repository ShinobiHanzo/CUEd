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
| Library browsing | Artists (A–Z rail) → artist page (albums, singles, loose tracks, appears on) → numbered album page; Albums grid; Tracks; Genres. Search narrows the open view | `core/library/Discography.kt`, `ui/screens/ArtistScreen.kt`, `AlbumScreen.kt` |
| Developer mode | Settings switch, off by default. On: Termux and companion download backends with their tools, and *Theme colours* (the nine colours the app is drawn with, same keys as the desktop client, with presets); off: built-in downloader only and the standard look | `data/Settings.kt`, `ui/theme/Theme.kt`, `ui/screens/SettingsScreen.kt` |
| Bug reports | Settings → Report a bug (also on failed downloads and after a crash): description + redacted recent debug log → public GitHub issue, via the API with a token or the prefilled browser form without one | `support/BugReporter.kt` |
| Car mode | Sidebar toggle (or automatic on car UI mode): one screen of oversized controls, quick-play tiles, voice button, spoken replies. Android Auto browse tree via `MediaLibraryService` | `ui/screens/CarModeScreen.kt`, `playback/LibraryTree.kt` |
| Widget and lock screen | Resizable home-screen widget (bar, card, tall tile) fed by the playback service; opt-in full-screen player above the keyguard with clock, cover, scrubber and controls | `widget/`, `lockscreen/` |
| Voice | In-app mic, Google Assistant and Android Auto ("play <x> on CUEd"). Rule-based command parser, no model | `core/.../voice/VoiceCommands.kt`, `app/.../playback/VoiceResolver.kt` |
| BPM detection | Onset-energy autocorrelation with a tempo prior, octave-error handling and beat-phase estimate. Runs on-device, one track at a time | `core/.../dsp/BpmDetector.kt` |
| Spectrograph | Live FFT tapped from ExoPlayer's audio pipeline (no mic permission) drawn as the scrubber, or a plain bar. Toggle on the Now Playing screen | `core/.../dsp/Spectrogram.kt`, `app/.../playback/SpectrumTapProcessor.kt`, `ui/components/PositionScrubber.kt` |
| Gestures | Artwork: swipe left/right for next/previous, swipe up for sing-along lyrics over the blurred art, tap play/pause, double-tap favourite, hold for the menu. Mini player: swipe to skip | `ui/screens/NowPlayingScreen.kt`, `ui/components/MiniPlayer.kt` |
| Podcasts & audiobooks | Anything over 12 minutes is sorted out of the music library into its own page (drawer): resumes where you stopped, ±10/30 s and speed controls, never crossfaded or recommended. Movable by hand from the track menu |
| Lyrics | Embedded ID3 lyrics → `.lrc`/`.txt` sidecar → lrclib.net (toggle). Synced LRC scrolls with the song and seeks on tap; bulk download for the library | `core/.../lyrics/`, `app/.../lyrics/`, `ui/components/LyricsPanel.kt` |
| Library | MediaStore scan into Room; favourites; play/skip history | `app/.../data` |
| Genres | Read from file tags on every scan and normalised (aliases, ID3v1 codes, multi-genre splits); optional MusicBrainz fill for untagged files; hand edits are locked; sweeps to re-label or tidy the whole library | `core/.../genre/`, `app/.../genre/` |
| Smart lists | Trending (recency-weighted plays), Newly downloaded, Unplayed, Forgotten, Favourites, Recommended (genre/artist/tempo/co-play heuristics, with reasons shown) | `core/.../reco/SmartLists.kt`, `Recommender.kt` |
| Playlists | Create, rename, reorder, save any smart list or genre or "more like this" as a playlist | `ui/screens/PlaylistsScreen.kt` |
| Downloads | **Built-in** (default, no setup): Spotify/YouTube metadata → YouTube Music match → AAC download → tagged m4a, all in Kotlin. Or, with Settings → Developer mode on, `spotdl` via **Termux** on the phone or the **companion server** over LAN. Links from any platform, playlists expanded, share-sheet integration | `app/.../download`, `core/.../download/Matcher.kt`, `tools/spotdl-server` |
| Sharing | QR code + NFC (host-card emulation, phone-to-phone, plus NDEF stickers) carrying a `cued://share?…` payload with the source link, a local file URL and an APK URL. Local HTTP server serves the track and the app itself | `app/.../share`, `core/.../share/SharePayload.kt` |
| Stations (beta, off by default) | After Settings → Beta features → setup, *Start a Station* broadcasts what you play (metadata only, signed by a key made on the phone) over Nostr relays; followers tune in from anywhere, their phones fetch each track themselves and play it at the host's position. Following page, QR/NFC follow links, temporary cache with Keep | `app/.../station`, `core/.../station`, `core/.../crypto` |

## Get the app

**Site with download link, QR and manual:** https://shinobihanzo.github.io/CUEd/

Or download the latest APK from the **[Releases](https://github.com/ShinobiHanzo/CUEd/releases)** page
(stable link: `https://github.com/ShinobiHanzo/CUEd/releases/latest/download/CUEd.apk`) and sideload it.
Once installed, Settings → Updates checks GitHub Releases, downloads, verifies the SHA-256 and installs
in-app. Pre-releases are signed with the public dev key described in
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
APK and publishes it on the Releases page. `.github/workflows/pages.yml` publishes `site/` to GitHub Pages.

## Docs

- [Architecture](docs/architecture.md): modules, threading, how the two-deck engine reports one `Player`
- [Tempo-matched crossfade](docs/tempo-match.md): the maths, the knobs, the limits
- [Downloading with spotdl](docs/downloading.md): Termux vs companion setup
- [Sharing](docs/sharing.md): QR / NFC / local server protocol and what leaves the phone (nothing)
- [Stations](docs/stations.md): broadcast what you play to followers anywhere; keys, relays, the listener's cache
- [Desktop client](docs/desktop.md): pair with [CUEd-desktop](https://github.com/ShinobiHanzo/CUEd-desktop) over a QR code for backup, streaming, downloads and a relay of your own
- [Car mode and voice](docs/car-and-voice.md): sidebar car mode, Android Auto, what you can say
- [Widgets and the lock-screen player](docs/widgets-and-lockscreen.md): resizable home-screen widget, CUEd's own player above the keyguard
- [Bug reports](docs/bug-reports.md): what Report a bug sends, where, and how to read it
- [Library](docs/library.md): artists → albums → tracks, how grouping works
- [Lyrics](docs/lyrics.md): where lyrics come from and what goes online
- [Genres](docs/genres.md): where labels come from, normalisation, fixing existing tracks

## Status

Daily-driver stage. The core library is unit-tested; the Android module is built and
released from CI and runs on real devices (Android 16 tested). Still unverified in the
wild: the built-in downloader after the NewPipeExtractor 0.26 bump, the lock-screen
player on every OEM, and NFC on specific firmware. Report problems from inside the app
(Settings → Report a bug) so the debug log comes along. Issues and PRs welcome; keep
it offline-first.

## License

MIT. See [LICENSE](LICENSE).
