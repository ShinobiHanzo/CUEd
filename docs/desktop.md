# The desktop client

[CUEd-desktop](https://github.com/ShinobiHanzo/CUEd-desktop) is the computer-side half of CUEd:
one daemon (`cuedd`, Rust) with a web UI, a C++ audio engine and a Python download worker. It
pairs with the phone over a QR code and then backs the phone up, streams its library back, runs
spotdl downloads, and carries the phone's Stations traffic through an embedded Nostr relay. From
outside the house it is reached through a self-hosted funnel. Nothing in the path belongs to a
third party.

The wire protocol lives in that repository's `docs/protocol.md`; this page is the phone's view.

## What works with this app today

| Desktop feature | Phone side | Status |
|---|---|---|
| Desktop pulls a track from the Share screen | existing share server (`/track/<id>`, `/meta/<id>`) | works |
| Desktop pushes a track to the phone (⇪ in the desktop library → QR) | existing Receive screen scans a `cued://share` with `f` pointing at the desktop | works |
| spotdl downloads on the desktop | Downloads → Backend → Companion (LAN) → `http://<desktop>:8770` | works, same API as `tools/spotdl-server` |
| Stations relayed through the desktop | Stations → relays → add `ws://<desktop>:8770/relay` | works, it is a normal NIP-01 relay |
| Pairing, manifest sync, resumable upload/backup, streaming the desktop library, blob backup | **not yet in the app** | spec below; `core/desktop/PairLink.kt` is the first piece |

## What the phone still has to learn

1. **`cued://pair` links.** `PairLink.decode` (in `core`) already parses them and builds the
   `POST /api/pair` body with the HMAC proof. The manifest needs `<data android:scheme="cued" android:host="pair" />`
   next to `share` and `station`, and the Receive screen's paste box / QR scanner should route it.
   Pairing: try each `a` address (3 s connect timeout), then `f` with the certificate pinned to `c`
   (compare the SHA-256 of the leaf DER; no CA). Store the token, the desktop name, `k` and `r`.
   Offer to add `r` to the Stations relay list.
2. **A `DesktopSync` worker** (WorkManager, unmetered network by default):
   `POST /api/sync/manifest` with every library track (key = track id, sha256 of the file, size,
   mime, tags, bpm, source link) → upload each hash in `missing` with `PUT /api/sync/track/<sha>`,
   resuming from `HEAD`'s `X-Cued-Have` with `Content-Range`, `X-Cued-Meta` carrying the manifest
   entry. Hashing a library once is the slow part: cache sha256 per track in Room (a new column),
   recomputed when size or modified time changes.
3. **Streaming the desktop library.** `GET /api/library` and `GET /api/track/<sha>` (range
   requests, `?token=` accepted for players that cannot set headers) are enough for a "Desktop"
   folder in the library drawer whose items play over HTTP, cached like station tracks.
4. **Backup of what is not a file:** playlists export, settings, and the station key encrypted
   with a passphrase, as blobs (`PUT /api/backup/blob/<name>`). The desktop never sees inside.
5. **Settings → Desktop:** linked desktops, last sync, sync on Wi-Fi only, unlink.

All of this is additive; nothing existing changes shape.

## Why it is shaped this way

- The desktop is a peer with a bigger disk, not an authority. Every file is content-addressed and
  verified by hash; every station event is signed by the phone; the relay cannot forge anything.
- Same crypto, same JSON: `core/crypto` and `core/station` are mirrored in the desktop's
  `cued-proto` crate and tested against the same BIP-340 vectors, so a state signed on the phone
  verifies on the desktop and the other way round.
- The funnel is the user's own box. The phone pins the desktop's certificate from the QR, so even
  that box only ever sees ciphertext.
