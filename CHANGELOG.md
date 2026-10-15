# Changelog

The sections below are shown in the app after an update (side menu → Settings → Updates → What's new),
so keep them short and written for the person holding the phone.

## 0.3.1

- **What's new after an update.** The first launch on a new version shows these notes; Settings →
  Updates → *What's new* brings them back. The notes ship inside the app, so they work offline.
- **Read before you download.** When an update is available, the home-screen banner opens a window
  with that version's notes and the Download button inside it; from Settings the same window stays
  open while the download runs. Release pages carry the same notes.

## 0.3.0

- **Karaoke**: cut the lead vocal and keep the bass. Settings → Karaoke, or the mic-off button on Now
  Playing. Works on stereo mixes; wide backing vocals stay, mono files go quiet.
- **Your ledger** (side menu): plays, listening time, new-to-you tracks, streaks, when you listen, and
  your top artists, tracks, albums and genres for any period. Counted on the phone; export as JSON.
- **Party** (side menu): a shared queue for friends. Host from this phone over Wi-Fi and Bluetooth, or
  join a friend's party (or their desktop) and queue songs from their library. Only people in your
  friends list get through, in both directions.

## 0.2.6

- Downloads → Companion (LAN) sends the paired desktop's token, as the desktop 0.2.4+ requires.

## 0.2.5

- Shared album links are rebuilt as "Artist – Album" playlists, like playlist links.
- Car mode can switch on by itself when you are moving: with the location permission, a short
  prompt appears above 30 km/h (adjustable) and car mode takes over if you do not answer.

## 0.2.4

- The downloader rebuilds a shared Spotify (or YouTube, Deezer) playlist as a CUEd playlist in the
  original order; tracks you already own slot in without a download.

## 0.2.3

- New releases from artists you follow and *Your favourite artists* on the home page; artist pages
  list a discography, and songs you lack stream while they download.
- Spotify as a feed source (with your own keys) and anything else that looks fishy sit behind
  Developer mode.

## 0.2.2

- The phone keeps its banner mini player; the floating one is desktop only.

## 0.2.1

- Developer mode gates the Termux and companion backends and the theme colours.
- Desktop link parity: friends, listening in, shared settings, biometric-locked reads from outside.

## 0.2.0

- Pair with the CUEd desktop over a QR code: backup, streaming, downloads, a relay of your own.
