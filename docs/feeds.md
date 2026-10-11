# New releases and artist feeds

Two rows on the home page, fed by Spotify's Web API when you have set your own client keys and by
[MusicBrainz](https://musicbrainz.org) (open data, no key, one request per second) otherwise, and
cached in the app's database so they read fine offline:

- **New releases.** Albums, EPs and singles from the last 120 days (and announced ones) by the
  artists you follow. Tap one for its track list.
- **Your artists.** The artists you follow. Each opens an RSS-like page of everything they have
  released, newest first; tap a release for its tracks. Under the row, one-tap *Follow* chips
  suggest the artists you play most but do not follow yet.

Follow an artist with the star on their library page or their feed page. Only followed artists
feed the home rows and the daily refresh; an artist you merely open is fetched for that page.

## Tracks you do not have yet

A release page lists every track with its metadata and the cover whether or not the audio is on
the phone. Tracks already in the library are marked and play from the library. For the rest:

1. **Tap to play from there.** Library copies play as they are. The first song you do not have
   is found on YouTube Music (the same matcher the downloader uses), streams at once and downloads
   behind the stream; every later missing song only downloads, and each joins the play queue in
   release order as its file lands. The stream is a temporary row, hidden from the library, that
   disappears when its download lands (or after 48 hours). One song's bytes travel twice; the rest
   once.
2. **The cloud icon** downloads without playing. *Download* on the release page queues every
   track you lack.

## Spotify or MusicBrainz

| | Spotify (your keys) | MusicBrainz (default) |
|---|---|---|
| Needs | Settings → Developer mode on, a free developer app at developer.spotify.com, client id and secret under Downloads → Built-in | nothing |
| Release dates | to the day, singles the day they drop | often the year only for recent singles; volunteers enter data |
| Covers | always | Cover Art Archive, missing for many smaller releases |
| Downloads | queued as Spotify track links, so the file gets Spotify's title, album, cover and genres | queued as an artist-and-title search |
| Caveats | Spotify has been tightening development-mode access since late 2024, and its terms frown on using the catalogue to fetch audio elsewhere; your keys, your call | one request per second, so a first pass over many artists takes a minute |

Spotify is a **developer-mode** source: with Developer mode off the feed is MusicBrainz only and
the key fields are hidden (saved keys are kept, not used). With it on, Settings → *New releases &
artist feeds* → *Metadata source*: **Auto** picks Spotify when keys are set, or force either.
Switching sources re-reads each artist on its next refresh.

## What goes online

- Spotify (with keys) or MusicBrainz: artist search, the discography, one release's track list.
  About one to three small requests per artist, once a day, Wi-Fi only unless you allow mobile
  data in Settings.
- Cover Art Archive: the cover thumbnails (redirects to archive.org).
- YouTube Music: only when you play or download a track.

Settings → *New releases & artist feeds* turns the rows off, allows mobile data, or refreshes now.
Everything is in `app/.../feed/FeedRepository.kt`, the pure rules (what counts as new, which
artists count as listened to, how partial dates are read) in `core/.../feed/ReleaseFeed.kt`.
