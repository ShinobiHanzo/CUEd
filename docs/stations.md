# Stations and following

A station is a person broadcasting *what they are playing*, not the audio.
Followers anywhere in the world hear the same track at the same position,
each from a copy on their own phone. Nothing here needs an account.

## How it works

| Piece | What it does |
|---|---|
| Identity | A secp256k1 key pair made on the phone on first use (`StationStore`). The public key is your station's address (`npub…`); the secret (`nsec…`) stays on the phone and can be exported to move to another phone. No server ever sees it. |
| Follow link | `cued://station?p=<pubkey>&n=<name>&r=<relays>` (`StationLink`), shown as a QR code and offered over NFC while *Your station* is open, exactly like a track share. Following stores only the key and name, locally. |
| State events | While on air the host signs a small JSON state (`StationState`: status, now playing with title/artist/album/source link, the next three queue entries, wall-clock `startedAt`) every time the player changes and every 30 s. It is a Nostr parameterised-replaceable event (kind `30777`, `d` tag `cued`), so a relay keeps only the newest per host. `Nostr`, `Secp256k1` and `Bech32` in `core/` are tested against the BIP-340 and NIP-19 vectors. |
| Relays | Plain Nostr relays over WebSocket (`NostrClient`): dumb store-and-forward boxes. Defaults are public, free and account-less; any `wss://` URL can replace them, including your own. Sockets are held only while a station is on air, a station is tuned in, or the Following page is open. |
| Listener | `StationListener` subscribes to one host, verifies every event's signature, has `StationCache` resolve each entry (library match by title and artist, else a temporary download from the source link via the built-in downloader, no transcoding), and plays it at `now − startedAt`. Drift is corrected by nudging speed ±3 %; a seek only happens past 2.5 s. The current and next three entries are prefetched, two at a time. |
| Cache | Temporary tracks live in the app cache as rows of kind `station`, hidden from the library and never marked missing by a rescan. A size cap (default 300 MB) evicts oldest first; *Keep this track* copies one into Music/CUEd and makes it a normal library track. |

## Why relays and not direct P2P

Two phones in different countries cannot open a connection to each other:
both sit behind carrier NAT that drops unsolicited inbound traffic. Every
"P2P" app either hole-punches with the help of a rendezvous server and
falls back to a relay when that fails (symmetric NAT, most mobile
carriers), or routes through something reachable. Since a station's whole
payload is a few hundred signed bytes per track change, a dumb relay is
the honest choice: it cannot forge anything (signatures), it learns only
what you broadcast, and it is interchangeable and self-hostable. A direct
WebRTC transport can be added later behind the same `NostrClient` surface
for pairs of phones that can reach each other; it would change latency,
not trust.

## What leaves the phone

- Host: station name, and for the current and next three tracks their
  title, artist, album, duration and source link. Never audio, never the
  library, never who follows you.
- Listener: nothing is published. Downloads go to the source (YouTube
  Music) exactly as the normal downloader does.
- Follow lists are not published (no Nostr kind-3 contact list), so the
  relay cannot see who follows whom.

## Limits

- Joining mid-track means waiting for that track to download first;
  prefetch hides this for everything after.
- A track the source refuses (geo-block, removed) fails on that listener
  with a reason and a retry; the station moves on with the next one.
- Playing anything else on the listening phone leaves the station.
- Stations are unlisted, not private: anyone who has the public key can
  read the state. Encrypting to followers only is the obvious next step.
