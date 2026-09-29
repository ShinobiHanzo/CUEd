# Lyrics

Tap the **Lyrics** icon on the Now Playing screen. The panel replaces the artwork; swipe
and tap gestures keep working underneath it.

## Where they come from, in order

1. **Embedded in the file.** spotdl writes lyrics into the ID3 tag (USLT frame) of what
   it downloads, so most tracks that came through CUEd already carry their words. Read
   by `core/.../lyrics/Id3Lyrics.kt`; only the tag is read, never the audio. MP3 only
   for now; M4A `©lyr` atoms are a to-do.
2. **A sidecar file** with the same name as the track: `Song.lrc` or `Song.txt`. Note
   that on Android 11+ apps can only read non-media files in their own folders or
   Downloads, so this works for `Music/CUEd` on older phones and for anything the
   system lets us at.
3. **lrclib.net**, an open, key-less lyrics database with both plain and LRC-synced text.
   This is the only host CUEd ever talks to outside your LAN. It is on by default
   because you asked for downloadable lyrics; turn it off in Settings → Lyrics and
   nothing leaves the phone. The request carries artist, title, album and duration,
   nothing else.

Whatever is found is cached in the local database. "Not found" is cached too and
retried after a week.

## Synced vs plain

LRC text (`[mm:ss.xx] line`) scrolls with playback, highlights the current line, and
tapping a line seeks to it. Plain text just scrolls. The parser
(`core/.../lyrics/Lrc.kt`) handles multiple timestamps per line and the `[offset:]` tag.

## Whole library

Settings → Lyrics → **Download lyrics for whole library** walks every track without
lyrics, tries the three sources in order, and paces online requests at about two per
second so lrclib isn't hammered. Progress and a Stop button are shown while it runs.
