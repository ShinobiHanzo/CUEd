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

## Round 2: what the phone must add

The desktop now enforces three tiers, carries accounts on a signed chain,
knows friends, lets friends listen in, and syncs a few settings. The phone
side of each, in the order that makes the rest work:

1. **Biometric key.** At pairing, make an EC P-256 key in the Android
   Keystore with `setUserAuthenticationRequired(true)` (biometric or device
   credential) and send its public half as `biokey` in `POST /api/pair`
   (SEC1 uncompressed, hex). Away from the LAN the desktop is read-only and
   every read needs `X-Cued-Assertion`: `GET /api/auth/challenge`, show
   `BiometricPrompt`, sign `cued-bio|<device>|<challenge>` with the key,
   send `<challenge>.<sig hex>`; keep it for 10 minutes. Writes (sync,
   upload, settings, friends) only happen on the LAN; the client should
   check the tier before trying and say so instead of showing a 403.
2. **Account at pairing.** The pairing screen offers *Join this desktop's
   account*, *Keep my account on both* or *Separate*. `join` opens the
   `account.sealed` from the pair response with `Seal.open(secret bytes,
   "account", …)` (`core/desktop/Seal.kt`) and replaces the station key with
   the bundle's; `keep` sends `POST /api/account/import` with the phone's
   key and chain sealed the same way. The chain (`core/desktop/AccountChain.kt`)
   is kept in the phone's files and merged with `GET`/`POST /api/account/chain`
   on every LAN sync, so settings and device lists converge. Only public keys
   are ever shown to anyone else; the nsec stays in the Keystore-backed store
   and in sealed transfers.
3. **Friends.** A *Friends* screen: my `cued://friend` QR (`FriendLink.kt`),
   NFC offer of the same link exactly like a share, scan or paste to add,
   requests with accept/decline, remove. The share page gets a "send a
   friend request with it" toggle that fills `friendPubkey`/`friendName`
   on the `SharePayload`; the Receive screen offers "add as friend" when a
   share carries them. Friend state lives on the phone and is pushed to the
   desktop's `/api/friends` on the LAN; kind-30778 events carry it over
   relays.
4. **Listening in.** A friend's station is metadata only (current track
   plus the next three). When the host's desktop offers `stream`, `cover`
   and `lyrics` capability URLs on each entry, the phone resolves in this
   order: own library, host stream into a temporary file, source link
   through the downloader; plays at `now − startedAt`, corrects drift only
   past 3 s, and keeps temporary audio, thumbnails, lyrics and metadata for
   48 hours (a new `kind = 'listen'` cache row class next to `station`).
5. **Shared settings.** `GET /api/settings/shared` after every sync: the
   relay switch (`relayEnabled`, off by default; when on, add the desktop
   relay URL to the Stations relay list), `friendStreaming`, the theme
   colours and the station name. Changes made on the phone go through
   `PUT /api/settings/shared` on the LAN and land in the chain.
6. **Theme colours** under Settings as a sub-page, driven by the same keys
   (`bg, panel, panel2, text, muted, accent, accent2, danger, border`), with
   the desktop's four presets.
7. **Mini player.** 150×150 dp bottom-right, cover as background, the live
   spectrograph drawn over it at 30 % opacity pinned to the bottom edge,
   close button and an italic *L* beside it; *L* grows the card to 150×400
   with the cover blurred behind synced lyrics (the existing `LyricsPanel`
   logic, centred current line).
