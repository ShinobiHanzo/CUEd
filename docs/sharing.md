# Sharing

Everything is peer-to-peer. The payload is a short URI:

```
cued://share?v=1&t=<title>&a=<artist>&l=<source link>&f=<file url>&k=<apk url>&g=<genres>&b=<bpm>
```

- `l` is a Spotify/YouTube URL the receiver hands to spotdl.
- `f` is `http://<sender-ip>:8765/track/<id>`, served by the sender's local share server
  while its Share screen is open (only the tracks being shared are served; nothing else is
  browsable).
- `k` is `http://<sender-ip>:8765/apk`: the receiving phone can install CUEd from the
  sender, no store needed. Opening `http://<sender-ip>:8765/` in any browser shows a plain
  page with the same links for phones that don't have the app yet.

## Channels

| Channel | How |
|---|---|
| QR | Sender shows the code; receiver scans it in CUEd (camera, zxing, no Play Services) or with any camera app, which opens the `cued://` link if CUEd is installed |
| NFC phone-to-phone | Sender runs host-card emulation (`CuedHceService`, AID `F043554544`); receiver's Receive screen is in reader mode and reads the payload in 250-byte chunks. Works on Android 10+ where Android Beam no longer exists |
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
