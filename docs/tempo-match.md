# Tempo-matched crossfade

## The idea

Two tracks rarely share a BPM, but they often share a *relation*. 85 BPM hip-hop sits
under 170 BPM drum & bass; 96 and 144 lock at 3:2. Instead of forcing the incoming track
to the outgoing BPM, `TempoMatcher.plan(out, in)` tries each ratio in

```
1:1  2:1  1:2  3:2  2:3  4:3  3:4  3:1  1:3
```

and keeps the one where `|ln(outBpm / (inBpm * ratio))|` is smallest: the "closest
common factor". The playback-speed change that ratio needs is `startSpeed`. If it is
more than `maxStretch` (default 8%) the plan is marked `matched = false` and a plain
crossfade happens instead, because pitch-preserving time-stretch starts to sound wobbly
beyond that.

## During the blend

Both decks glide geometrically so the ratio between them stays constant and the beats
stay locked:

- incoming: `startSpeed → 1.0`
- outgoing: `1.0 → 1/startSpeed`

Both end on the incoming track's native tempo, which is what a DJ does with the pitch
fader. `MixTest` checks that `outBpm * outSpeed == inBpm * inSpeed` at every step.

## Beat alignment

`TransitionPlanner` snaps the blend start on the outgoing track to its beat grid
(`firstBeatSec + k * 60/bpm`) and starts the incoming track at its own first beat, so
`t = 0` of the blend is a beat on both. Bar-level alignment (downbeats) is a future
improvement; the grid does not currently know where bar 1 is.

## Knobs (Settings → Crossfade / Tempo match)

| Setting | Default | Effect |
|---|---|---|
| Duration | 6 s | Blend length; 0 = gapless, no overlap |
| Curve | Equal power | `cos/sin` keeps loudness flat; Linear dips in the middle; Smooth holds then drops |
| Tempo-match | on | Off = plain crossfade always |
| Max stretch | 8% | Fallback threshold |
| Min BPM confidence | 25% | Tracks whose detector confidence is below this are not matched |

## Limits, honestly

- BPM detection is autocorrelation with a prior, not a beat tracker. It is fine for
  four-on-the-floor and most pop/hip-hop, less so for rubato or heavily swung material.
  Confidence is exposed so weak detections just don't match.
- Speed changes use ExoPlayer's Sonic time-stretch (pitch preserved). Quality is good
  within a few percent, audible around 8–10%.
- Both decks decode concurrently for the length of the blend. On a very low-end phone
  with FLAC on both sides, keep the blend short.
