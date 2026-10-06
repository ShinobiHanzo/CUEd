# Sharing

Everything is peer-to-peer. The payload is a short URI:

```
cued://share?v=1&t=<title>&a=<artist>&l=<source link>&f=<file url>&k=<apk url>&g=<genres>&b=<bpm>
https://shinobihanzo.github.io/CUEd/#v=1&t=...        (same fields; what QR and NFC actually carry)
```

- `l` is a Spotify/YouTube URL the receiver hands to spotdl.
- `f` is `http://<sender-ip>:8765/track/<id>`, served by the sender's local share server
  while its Share screen is open (only the tracks being shared are served; nothing else is
  browsable).
- `k` is `http://<sender-ip>:8765/apk`: the receiving phone can install CUEd from the
  sender, no store needed. Only present when "Include a link to install CUEd" is switched
  on; it is off by default. Opening `http://<sender-ip>:8765/` in any browser shows a plain
  page with the same links for phones that don't have the app yet.

## Channels

| Channel | How |
|---|---|
| QR | Sender shows the code; receiver scans it in CUEd (camera, zxing, no Play Services) or with any camera app, which opens the `cued://` link if CUEd is installed |
| NFC phone-to-phone | The sender's Share screen turns the phone into an NFC Forum Type 4 tag (`CuedHceService`, NDEF AID `D2760000850101`) holding the share as URL records: the `cued://share?…` form first, the web form second. The receiver needs nothing open and must have CUEd: Android's NFC dispatcher only looks at the first record, matches the `cued` scheme to CUEd alone and launches it directly, with no "open link?" dialog. (An http(s) first record would go down Android's web-link path, which browsers also claim and which Android 15 turns into a plain VIEW intent that only a browser or a verified app link can win; a GitHub project page cannot serve the verification file at the domain root, so that route is closed.) A phone without CUEd gets nothing from the tap; it scans the QR instead, which carries the web form and lands on the download page with the share shown. While the Share screen is in front, `TagEmulationSession` (a) claims the NDEF AID dynamically and makes CUEd the preferred card-emulation service, so phones where wallets, Nearby or car keys share that AID (Samsung) do not ask which service to use; statically CUEd owns only its own AID `F043554544`, kept for receivers on 0.1.21 and earlier; (b) switches the sender's own tag polling off (Android 14+, `setDiscoveryTechnology`) and swallows any tag with foreground dispatch on older versions, so the sender never launches a tag-reader app when it sees the receiver's wallet as a card. Reader mode is never used on the sender (it disables card emulation); writing a sticker pauses the session. The Receive screen does not enter reader mode either, and its camera stays off until "Scan a QR code" is tapped: an open camera blocks NFC on some phones |
| NFC sticker | Share screen → "Write to an NFC sticker". Tapping the sticker later opens the `cued://` link |
| Message | "Send as a message" puts the URI on the share sheet; CUEd registers for the `cued://share` scheme |

## What the receiver does

1. If `f` is present and reachable, it pulls the file straight into `Music/CUEd`, tags it
   with the shared genres and remembers the source link.
2. Otherwise it queues `l` for spotdl (Termux or companion).
3. If neither, it just shows the title.

## Privacy

Nothing is uploaded anywhere. The share server binds to the phone's LAN address only
while the Share screen is open, and stops the moment you leave it. No analytics, no crash
reporting, no account.

## What the receiver sees

Every incoming share (tap, QR scan or pasted link) opens a full-screen card
(`ReceivedSplash`) with the title, artist, cover once it exists, what the share
contained (audio file, source link, tempo, genres) and a live status: copying
from the other phone, added to the library, already in the library, queued from
the source link, or what went wrong. It stays until closed so a person sees the
transfer land instead of tapping again. The same share arriving again is never
fetched twice: `ShareViewModel.receive` remembers what it has seen and only adds
a "tapped again" note to the card. "Copy it again anyway" forces a re-fetch.

