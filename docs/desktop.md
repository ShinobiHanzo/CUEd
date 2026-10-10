# The desktop client

[CUEd-desktop](https://github.com/ShinobiHanzo/CUEd-desktop) is the computer-side half of CUEd:
one daemon (`cuedd`, Rust) with a web UI, a C++ audio engine and a Python download worker. It
pairs with the phone over a QR code and then backs the phone up, streams its library back, runs
spotdl downloads, and carries the phone's Stations traffic through an embedded Nostr relay. From
outside the house it is reached through a self-hosted funnel. Nothing in the path belongs to a
third party.

The wire protocol lives in that repository's `docs/protocol.md`; this page is the phone's view.

## What works with this app today

| Desktop feature | Phone side | Where |
|---|---|---|
| Pairing over the `cued://pair` QR (scan, paste, tap, or open the link) with the account choice *join / keep / separate* | side menu → **Desktop**, or Receive screen | `ui/screens/DesktopScreens.kt`, `desktop/DesktopService.pair` |
| Three access tiers: writes only on the desktop's LAN; reads from outside signed with a biometric key | a P-256 key in the Android Keystore, `BiometricPrompt` on every assertion (10 min) | `desktop/BiometricSigner.kt`, `core/desktop/Assertion.kt` |
| Manifest sync and resumable, content-addressed backup | Desktop → *Back up now*; hashes cached per track (size + modified) | `DesktopService.sync`, `core/desktop/DesktopClient.upload` |
| Streaming the desktop library | Desktop → *Browse its library* → Play; the file lands in the 48-hour cache and plays like a station track | `DesktopLibraryScreen`, `station/StationCache.fetchFromUrl` |
| Account chain (`create / profile / bind_device / settings`) | merged on every LAN sync; `join` opens the sealed bundle, `keep` seals the phone's | `core/desktop/AccountChain.kt`, `Seal.kt` |
| Friends over QR, NFC, paste, a share's *send a friend request* toggle; kind-30778 events | side menu → **Friends**; share page toggle; Receive screen | `ui/screens/FriendsScreen.kt`, `ui/screens/ShareScreens.kt` |
| Listening in with the host's `stream` / `cover` / `lyrics` capability links | the listener's cache tries the stream URL first, then the downloader; 48-hour retention | `station/StationCache.kt`, `core/station/Station.kt` |
| Shared settings: relay switch (off by default), friend streaming, theme colours, station name | Desktop screen toggles; theme applied live; relay URL added/removed from the Stations relay list | `DesktopService.applyShared` |
| Theme colours sub-page | Settings → Developer mode → Theme colours | `ui/theme/Theme.kt`, `ui/screens/SettingsScreen.kt` |
| Floating mini player: 150×150 dp bottom-right, spectrograph at 30 % over the cover; italic *L* grows it to 150×400 with blurred cover and synced lyrics | every screen but the full player; × hides it until the next track | `ui/components/FloatingMiniPlayer.kt` |
| Desktop pulls a track from the Share screen; pushes one with its ⇪ button; spotdl companion; plain NIP-01 relay | unchanged from before | share server, Receive screen, Downloads backend, Stations relays |

The core half (`core/desktop/*`: `PairLink`, `FriendLink`, `Seal`, `AccountChain`, `Assertion`,
`DesktopClient`, the manifest types) is pure JVM and covered by unit tests, including a fake
desktop for the client. The Android half was written against the existing app APIs but has not
been compiled on a real SDK yet: the CI debug-APK job is its first build, and the first phone to
pair with a real desktop is its first run. Treat `desktop/` and the two new screens as beta.

## How a phone talks to a desktop

1. **Pair.** `PairLink.decode` parses the QR. The phone tries each LAN address with a 4 s connect
   timeout, then the funnel with the certificate pinned to the QR's `c` (SHA-256 of the leaf DER,
   no CA). `POST /api/pair` carries the HMAC proof, the phone's station public key, the biometric
   public key and a device hash. The dialog asks *join / keep / separate*; `join` replaces the
   phone's station key with the desktop account's (sealed to the pairing secret), `keep` pushes the
   phone's key and chain to the desktop the same way. The token is stored per desktop in a DataStore.
2. **Tiers.** On the LAN the bearer token is enough. Anywhere else the daemon is read-only and every
   read needs `X-Cued-Assertion`: `GET /api/auth/challenge`, a fingerprint or face, a signature over
   `cued-bio|<device>|<challenge>`, kept for just under ten minutes. The phone checks `GET /api/me`
   for its tier and says "writes only on the desktop's Wi-Fi" instead of showing a 403.
3. **Sync.** `POST /api/sync/manifest` with every library track (sha256 cached per size + modified
   time); each `missing` hash goes up with `PUT /api/sync/track/<sha>`, resuming from `HEAD`.
   Then the account chain is merged both ways, shared settings are pulled and applied, and friend
   state is pushed.
4. **Stream.** `GET /api/library` lists the desktop's tracks; a play streams `GET /api/track/<sha>`
   into the station cache (48 hours) and plays it like any received track.
5. **Friends and listening in.** Friend state lives on the phone, is pushed to `/api/friends` on the
   LAN and published as kind-30778 events over relays. When a friend's desktop has *friend
   streaming* on, their station entries carry `stream`, `cover` and `lyrics` capability links; the
   listener fetches those first and falls back to the source link.

## Why it is shaped this way

- The desktop is a peer with a bigger disk, not an authority. Every file is content-addressed and
  verified by hash; every station event is signed by the phone; the relay cannot forge anything.
- Same crypto, same JSON: `core/crypto` and `core/station` are mirrored in the desktop's
  `cued-proto` crate and tested against the same BIP-340 vectors, so a state signed on the phone
  verifies on the desktop and the other way round.
- The funnel is the user's own box. The phone pins the desktop's certificate from the QR, so even
  that box only ever sees ciphertext.

## What is still open

- Background sync (WorkManager, unmetered only) is not scheduled yet: backup runs when you tap
  *Back up now* or when the Desktop screen is open.
- A paired desktop's own library streams through the pinned client, so it works from outside the
  house. A *friend's* `stream` / `cover` / `lyrics` links carry no certificate pin yet, so from
  outside the LAN they only work when that friend's funnel has a certificate the phone already
  trusts; otherwise the listener falls back to the source link through the downloader.
- Blob backup of playlists and settings (`PUT /api/backup/blob/<name>`) has a client method but no
  screen yet.
- The `:app` module has not been compiled in the environment this was written in; expect a round of
  small fixes from the first CI build.
