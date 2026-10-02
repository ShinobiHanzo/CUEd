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
