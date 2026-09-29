# Architecture

```
CUEd/
├── core/   pure Kotlin (JVM). DSP, mixing maths, recommendation rules, share codec. Unit-tested.
├── app/    Android. Room + Media3 + Compose. Depends on :core.
└── tools/spotdl-server/   optional Python companion for LAN downloads.
```

## Why a `core` module

Everything that can be reasoned about without a phone lives in `core`: the FFT, the
spectrogram binning, BPM detection, the tempo-ratio search, crossfade gain/speed curves,
the smart-list rules, the recommender and the `cued://share` payload codec. It builds and
tests on any JDK, which is also why the CI job for it needs no Android SDK.

## Playback: two decks, one `Player`

ExoPlayer plays one item at a time, so a crossfade needs two of them. `CrossfadePlayer`
owns `Deck(0)` and `Deck(1)` and extends Media3's `SimpleBasePlayer`, which turns a
`State` snapshot into a full `Player`. `PlaybackService` wraps that in a `MediaSession`,
so the notification, lock screen, Bluetooth buttons and Android Auto all see a normal
player with a normal playlist.

Timeline of one transition:

1. About 40 s before the active deck ends, the engine **arms**: it asks `AnalysisQueue`
   for the tempo of the current and next track (analysing the next one at the front of
   the queue if needed) and computes a `TransitionPlan` with `TransitionPlanner`.
2. When the active deck reaches `plan.startAtOutgoingMs` (snapped to a beat), the other
   deck loads the next item at `plan.incomingStartMs` (its first beat), at the matched
   speed, volume 0, and starts.
3. Every 50 ms the tick applies `Crossfade.gains(progress)` and `Crossfade.speeds(progress)`.
   At 50% the engine swaps "active" so the session reports the new track; at 100% the old
   deck is stopped and reset.

Seeking, skipping, playlist edits or a deck error during a blend cancel it cleanly.
Audio focus and "becoming noisy" are handled once for the pair, not per deck.

## Spectrum tap

`SpectrumTapProcessor` is a pass-through `AudioProcessor` inserted into each deck's
`DefaultAudioSink`. It mirrors the 16-bit PCM into a `StreamingSpectrogram` and publishes
64-band frames to `SpectrumBus`. Because it sits before the audio track buffer it runs
slightly ahead of the speaker; the UI reads frames from `visualDelayMs` ago to compensate
(Settings → Scrubber). This is why the live view needs no microphone permission.

## Silence skipping

`CuedAudioProcessorChain` replaces ExoPlayer's default chain per deck:
silence skip → spectrum tap → Sonic time-stretch. `SilenceSkipProcessor` keeps the
first *tolerance* milliseconds of any quiet run (peak below the dBFS threshold) and
drops the rest until sound returns. It reports dropped frames through
`getSkippedOutputFrameCount`, which is how the sink keeps `currentPosition` truthful,
so the scrubber, the crossfade arming point and play-history fractions are unaffected.
Settings apply live; no reconfiguration.

## Analysis

`AnalysisQueue` decodes with `MediaCodec` to mono ~11 kHz and detects BPM from the first
8 minutes. One track at a time, background priority, but the player can push a track to
the front when it is about to be mixed in.

## Data

Room. `tracks` mirrors MediaStore and carries user state (favourite, genres via
`track_genres`, play/skip counts, analysis). `play_events` is the append-only history the
smart lists and recommender read. Files that disappear are flagged `missing`, not deleted,
so playlists survive an unplugged SD card.

## Long plays

`tracks.kind` is `music` or `long`; the scan sets it from duration (over 12 minutes =
long) and the track menu can move items either way. Every music query filters on
`kind = 'music'`, so smart lists, genres, search and the recommender never see podcasts.
Long plays carry `resumeMs`: the engine reports progress every 5 s and on pause, the
holder persists it, and `Deck.load` starts a long item from that offset. The engine also
refuses to arm a crossfade into or out of a long item and honours a listener speed
(`handleSetPlaybackParameters`) that resets to 1x whenever a song loads.

## Threading

- ExoPlayer and `SimpleBasePlayer` are driven on the main looper.
- Analysis runs on `Dispatchers.Default`, decoding included.
- The share server (NanoHTTPD) has its own threads and reads the DB with `runBlocking`
  for the few small queries it needs.
