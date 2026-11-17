# Changelog

The sections below are shown in the app after an update (side menu → Settings → Updates → What's new),
so keep them short and written for the person holding the phone.

## 0.3.4

- **Phone-to-phone sharing at Wi-Fi speed.** With no Wi-Fi network in common, the tap or the QR
  scan now links the two phones directly over Wi-Fi Direct, the way AirDrop works: a song
  arrives in a few seconds, with no router, hotspot, internet or pairing. Wi-Fi only has to be
  switched on. Allow *Nearby devices* once on each phone (Android 12 and older ask for Location
  instead and need it switched on; CUEd reads no location). Needs Android 10 or later.
- Bluetooth stays as the second choice and NFC alone as the last; the card says which one is
  running and why a faster one was skipped.

## 0.3.3

- **Share a song with no Wi-Fi at all.** Tap-to-share and the QR code used to hand over a Wi-Fi
  address, so both phones had to be on the same network. Now the tap (or the scan) only
  introduces the phones and the file crosses over Bluetooth by itself: no Wi-Fi, no hotspot, no
  internet, no pairing. Allow *Nearby devices* once on each phone; it needs Android 10 or later.
- **NFC alone as the last resort.** With Bluetooth off as well, the card that opens on a tap
  offers *Copy over NFC* (Receive has *Receive a file over NFC* too): hold the phones together
  until the bar fills. It is slow, a few minutes per song.
- Either way a dropped link only pauses the copy: it carries on from the same byte, and the file
  is hash-checked before it goes into your library.

## 0.3.2

- **No more karaoke by mistake.** The built-in downloader refuses karaoke, instrumental, cover and
  tribute uploads (by title and by channel, such as Sing King or KaraFun) unless you asked for one;
  it would rather find nothing than the wrong recording. "Artist - Title" uploads and the label's own
  *Topic* audio now score as they should, so the real song wins even when the official video runs long.

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
