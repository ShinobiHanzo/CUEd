# Genres

Every music track should carry at least one genre label, because the genre pages, the
recommender and voice ("play some house") all key off them. Here is where labels come
from and how to fix the ones that are wrong.

## Sources, in order

1. **The file's own genre tag** (ID3 `TCON`, MP4 genre atoms, Vorbis `GENRE`), read with
   the platform's metadata reader on every Android version. spotdl writes Spotify's artist
   genres into this tag, so downloads made through CUEd normally arrive labelled. After
   every library scan, tracks with no label are read this way in the background.
2. **MusicBrainz** (opt-in, Settings → Genres) for tracks whose files carry nothing at
   all: open data, no account, one request a second by their rules. It looks the
   recording up by artist and title and takes the community's top genre votes, falling
   back to the artist's genres. Nothing but artist and title is sent.
3. **You.** Track menu → Edit genres. Anything edited by hand is locked and never
   re-labelled automatically; "Let automatic labelling change this track again" unlocks it.

## Normalisation

Raw tag values are messy. `core/.../genre/GenreNormalizer.kt` splits multi-genre strings
(`;` `,` `/` `|`), expands ID3v1 numeric codes (`(17)` → rock), lowercases, and folds
aliases so "Hip-Hop", "hiphop" and "Rap/Hip Hop" all become `hip hop`, "DnB" / "D&B" /
"Drum & Bass" become `drum and bass`, and junk like "Unknown" or "Other" is dropped. The
table is plain Kotlin; add a line to extend it. It is unit-tested.

## Fixing existing tracks

Settings → Genres:

| Button | What it does |
|---|---|
| Label unlabelled from tags | Reads the tag for every music track with no genre |
| Re-read all tags | Same, for every track not locked by a hand edit; replaces existing labels when the tag has something |
| Tidy labels | No file access: normalises and merges the labels already in the database |
| Look up unlabelled tracks now | MusicBrainz, when enabled |

The Library screen shows an **unlabelled (N)** chip while any music track has no genre;
tap it for the list.

## What is deliberately not here

No audio-based genre classification. It would need a trained model, and this app has
none by design. Tags plus open metadata plus your own labels get a library to "all
categorised" faster and more honestly than a classifier that guesses.
