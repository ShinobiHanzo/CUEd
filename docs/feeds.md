# New releases and artist feeds

Two rows on the home page, both fed by [MusicBrainz](https://musicbrainz.org) (open data, no key,
one request per second) and cached in the app's database so they read fine offline:

- **New releases.** Albums, EPs and singles from the last 120 days (and announced ones) by the
  artists you play most and the artists you follow. Tap one for its track list.
- **Your artists.** The artists you follow. Each opens an RSS-like page of everything they have
  released, newest first; tap a release for its tracks.

Follow an artist with the star on their library page or their feed page. Artists you play often
are watched automatically without being listed under *Your artists*; following them pins them.

## Tracks you do not have yet

A release page lists every track with its metadata and the cover whether or not the audio is on
the phone. Tracks already in the library are marked and play from the library. For the rest:

1. **Tap to play.** The app finds the song on YouTube Music (the same matcher the downloader
   uses), starts streaming it at once, and queues the real file in the normal download queue. The
   stream is a temporary row, hidden from the library, that disappears when the download lands
   (or after 48 hours). Streaming plus the download means the song's bytes travel twice; on
   mobile data that is worth knowing.
2. **The cloud icon** downloads without playing. *Download* on the release page queues every
   track you lack.

## What goes online

- MusicBrainz: artist search, release groups, one release's track list. About one to three small
  requests per artist, once a day, Wi-Fi only unless you allow mobile data in Settings.
- Cover Art Archive: the cover thumbnails (redirects to archive.org).
- YouTube Music: only when you play or download a track.

Settings → *New releases & artist feeds* turns the rows off, allows mobile data, or refreshes now.
Everything is in `app/.../feed/FeedRepository.kt`, the pure rules (what counts as new, which
artists count as listened to, how partial dates are read) in `core/.../feed/ReleaseFeed.kt`.
