# Sharing

Everything is peer-to-peer. The payload is a short URI:

```
cued://share?v=1&t=<title>&a=<artist>&l=<source link>&f=<file url>&k=<apk url>&g=<genres>&b=<bpm>&bt=<token>&n=1
https://shinobihanzo.github.io/CUEd/#v=1&t=...        (same fields; what QR and NFC actually carry)
```

- `l` is a Spotify/YouTube URL the receiver hands to spotdl.
- `f` is `http://<sender-ip>:8765/track/<id>`, served by the sender's local share server
  while its Share screen is open (only the tracks being shared are served; nothing else is
  browsable).
- `bt` is a one-time token (16 hex characters): the sender serves the file over Bluetooth to
  whoever presents it (below). Present when "Include the audio file" is on and Bluetooth is
  usable on the sender.
- `n=1` says the sender's Share screen also hands the file out over NFC alone (below). It is
  set whenever "Include the audio file" is on, with or without a Wi-Fi address.
- `k` is `http://<sender-ip>:8765/apk`: the receiving phone can install CUEd from the
  sender, no store needed. Only present when "Include a link to install CUEd" is switched
  on; it is off by default. Opening `http://<sender-ip>:8765/` in any browser shows a plain
  page with the same links for phones that don't have the app yet.

## Channels

| Channel | How |
|---|---|
| QR | Sender shows the code; receiver scans it in CUEd (camera, zxing, no Play Services) or with any camera app, which opens the `cued://` link if CUEd is installed |
| NFC phone-to-phone | The sender's Share screen turns the phone into an NFC Forum Type 4 tag (`CuedHceService`, NDEF AID `D2760000850101`) holding the share as URL records: the `cued://share?…` form first, the web form second. The receiver needs nothing open and must have CUEd: Android's NFC dispatcher only looks at the first record, matches the `cued` scheme to CUEd alone and launches it directly, with no "open link?" dialog. (An http(s) first record would go down Android's web-link path, which browsers also claim and which Android 15 turns into a plain VIEW intent that only a browser or a verified app link can win; a GitHub project page cannot serve the verification file at the domain root, so that route is closed.) A phone without CUEd gets nothing from the tap; it scans the QR instead, which carries the web form and lands on the download page with the share shown. While the Share screen is in front, `TagEmulationSession` (a) claims the NDEF AID dynamically and makes CUEd the preferred card-emulation service, so phones where wallets, Nearby or car keys share that AID (Samsung) do not ask which service to use; statically CUEd owns only its own AID `F043554544`, kept for receivers on 0.1.21 and earlier; (b) switches the sender's own tag polling off (Android 14+, `setDiscoveryTechnology`) and swallows any tag with foreground dispatch on older versions, so the sender never launches a tag-reader app when it sees the receiver's wallet as a card. Reader mode is never used on the sender (it disables card emulation); writing a sticker pauses the session. The Receive screen does not enter reader mode either, and its camera stays off until "Scan a QR code" is tapped: an open camera blocks NFC on some phones |
| Bluetooth file transfer (no network) | The `f` link only works when both phones sit on one Wi-Fi or hotspot. Without that, the tap or the QR scan is only the introduction and Bluetooth carries the file. The sender (`BtFileSender`) listens on a Bluetooth LE connection-oriented channel (L2CAP, insecure, so no pairing dialog; Android 10+) and advertises 12 bytes of manufacturer data: `CU`, the 8-byte token from the share, and the channel number (PSM). No name and no service list are advertised. The receiver (`BtFileReceiver`) scans with a filter on exactly that token, connects, and speaks `dev.cued.core.nfc.BtTransfer`: hello with the token, a header back (sha256, size, mime, title, artist, link, genres, bpm), the offset to start from, then the bytes, then its verdict. A connection that does not present the token is refused before anything is sent. The copy runs by itself as soon as the share arrives, from any screen, and needs only the Nearby devices permission (asked once on each phone); expect a 4 MB song in well under a minute or two, at any distance Bluetooth reaches. Bytes land in a `.part` file named after the hash (`PartialFile`), so a dropped link reconnects and carries on (three attempts, then the card offers a retry), and the final hash check covers the whole file. The link is not encrypted, like the plain-HTTP Wi-Fi copy |
| NFC file transfer (no other radio) | The last resort, for when Bluetooth is off or refused: the whole file crosses over NFC by itself. The sender stays the card (same `CuedHceService`, under the CUEd AID `F043554544`) and the receiver becomes the reader (`NfcFileReceiver`, `enableReaderMode`, ISO-DEP). The reader asks `INFO` (CLA 80 INS C0) for the same header and then `GET offset,len` (INS C1) slice by slice, up to 16 KB a slice when the reader's NFC stack supports extended length, 250 bytes otherwise; a slice the pair cannot carry is halved until it fits. At the end it tells the card the verdict (INS C2). It uses the same `.part` file as Bluetooth, so a tap that slips carries on from that byte on the next tap, and a copy begun over one radio can finish over the other. The sender memory-maps the file once (`OfferedFile`) for both radios. The protocol is `dev.cued.core.nfc.NfcTransfer`; both it and `BtTransfer` are pure JVM and tested end to end (whole file, resume, oversized slices, wrong token, a vanished offer). Speed is NFC's: roughly 5–30 KB/s, so a 4 MB song takes two to ten minutes of the phones touching. The receiver's card shows *Copy over NFC* when the share says `n=1`; Receive → *Receive a file over NFC* does the same without a prior tap. Reader mode ends when the copy lands, when the card is closed, or when the Receive screen is left; while it is on, the ordinary NDEF tap-to-open is not dispatched on that phone |
| NFC sticker | Share screen → "Write to an NFC sticker". Tapping the sticker later opens the `cued://` link |
| Message | "Send as a message" puts the URI on the share sheet; CUEd registers for the `cued://share` scheme |

## What the receiver does

1. If `f` is present and reachable, it pulls the file straight into `Music/CUEd`, tags it
   with the shared genres and remembers the source link.
2. If `f` is missing or out of reach and `bt` is present, it copies the file over Bluetooth
   by itself. When that needs a person (the permission, Bluetooth switched off) the card
   says so and offers *Copy over Bluetooth*; with `n=1` it also offers *Copy over NFC*, and
   when `l` exists, *Download from the source link instead*.
3. Otherwise it queues `l` for spotdl (Termux or companion).
4. If none of those, it just shows the title.

## Privacy

Nothing is uploaded anywhere. The share server binds to the phone's LAN address only
while the Share screen is open, and stops the moment you leave it. No analytics, no crash
reporting, no account.

## What the receiver sees

Every incoming share (tap, QR scan or pasted link) opens a full-screen card
(`ReceivedSplash`) with the title, artist, cover once it exists, what the share
contained (audio file, source link, tempo, genres) and a live status: copying
from the other phone, copying over Bluetooth or NFC with a bar in bytes, offered
phone to phone and waiting for a button,
added to the library, already in the library, queued from the source link, or
what went wrong. It stays until closed so a person sees the
transfer land instead of tapping again. The same share arriving again is never
fetched twice: `ShareViewModel.receive` remembers what it has seen and only adds
a "tapped again" note to the card. "Copy it again anyway" forces a re-fetch.

