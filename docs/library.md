# Library: artists, albums, tracks, genres

The Library tab has four views, and the search box narrows whichever one is open.
That is the whole idea: drill in step by step instead of scrolling one long list.

```
Artists ──▶ artist page ──▶ album page ──▶ track
  A–Z rail    albums          numbered tracks
  search      singles & EPs   disc headers
              loose tracks    play / shuffle / save as playlist
              appears on
Albums  ──▶ album page
Tracks  ──▶ flat list (title / artist / album search)
Genres  ──▶ genre list
```

Every track's menu also has **Go to artist** and **Go to album**.

## How tracks are grouped

The rules live in `core/.../library/Discography.kt` and are unit-tested; nothing
is learned or guessed from the network.

- An album belongs to its **album-artist** tag when the file has one, otherwise to
  the **primary artist** of the track: the part before "feat.", "ft.", "featuring"
  or "with". So *Get Lucky* by "Daft Punk feat. Pharrell Williams" sits inside
  *Random Access Memories* under Daft Punk.
- Albums are keyed by (owner, album name), not by MediaStore's album id, so an
  album split across two folders is still one album.
- 1 to 3 tracks under one album name count as a **single / EP**.
- Tracks with no album tag are listed as loose **Tracks** on the artist page.
- **Appears on** lists tracks that credit the artist ("feat.", "&", ",", "x", "/")
  but live under someone else's release. A featured-only artist still gets a page.
- Compilations tagged "Various Artists" get their own page; each track also shows
  under its own artist's *Appears on*.
- Sorting ignores a leading "The"; the A–Z rail puts digits and symbols under "#".

Track numbers, disc numbers, years and album-artist come from MediaStore on every
rescan (album-artist on Android 11+). Files tagged by the built-in downloader carry
all four.

## Long-press and delete

Hold any track (rows, album tracks, Home tiles, podcasts) for its menu; the ⋮ button
does the same. **Delete from device** at the bottom removes the file from the phone,
not just from CUEd, after a confirmation. Files CUEd downloaded itself go straight
away; files from Termux, the companion or other apps belong to someone else in
MediaStore's eyes, so Android 10+ shows its own confirmation dialog first (one for
the whole batch on Android 11+). The album page's bin icon deletes a whole album.
Playlist entries, genres, lyrics and the queue entry go with the track.

## Track details

Track menu → **Track details**. Shows what CUEd has (editable), what is actually inside
the file (title, artist, album, track, year, genre, cover size, format, bitrate, path),
and offers: **Find cover** (source link first: Spotify cover or YouTube thumbnail; then
MusicBrainz + Cover Art Archive; then the top YouTube Music hit), **Look up online**
(MusicBrainz fills album, album artist, year and track number), **Reload from file**,
**Write to file** (rewrites the tags: CUEd's own ID3v2 writer for mp3, jaudiotagger
for m4a; files CUEd didn't create need Android's one-tap consent on 10+), **Save in
CUEd only**, and **Re-download** (source link, or a search on title + artist).

Downloads from YouTube links and searches used to arrive with the video title, the
channel as artist and no album, so Android filed them under an album named after the
folder ("CUEd"). Now the title is cleaned ("(Official Video)" and friends removed,
"Artist - Title" split), the channel name loses "- Topic"/"VEVO", and, when the
Downloads toggle allows, MusicBrainz supplies album, year, track number and cover. The
cover fetch tries several YouTube thumbnail sizes (maxres is often missing) and
centre-crops the 16:9 frame to a square.
