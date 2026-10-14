# Party: a shared queue for friends

Side menu → **Party**. One phone (or a [CUEd desktop](desktop.md)) hosts; friends on the
same Wi-Fi, or next to it over Bluetooth, search the host's library and add songs to its
queue. Spotify Jam needs accounts, Premium and their servers; this needs a friends list and a
room.

## Hosting

Flip **Host a party on this phone**. The screen shows the Wi-Fi address guests reach you on
and the Bluetooth state (advertising). **Guests may control playback** lets them skip, pause
and resume as well as queue; off means queue only. Every guest action shows up under *Guest
activity* with the friend's name.

Only people in your **Friends** list with an accepted request can join. Each request they send
is signed with their station key; the host checks the signature, that the clock is within
90 s, that the request is not a replay, and that the key is a friend. Nothing else gets an
answer beyond "not a friend".

## Joining

**Find a party** broadcasts a question on the Wi-Fi (UDP 8772) and, with the *Nearby devices*
permission, scans for phones advertising the party service over Bluetooth LE. Tap a host: the
phone sends a signed `hello`, checks the host's signed reply, and refuses to go on unless the
host's key is in *your* friends list too. Both sides vouch.

Then: search, tap a result to queue it, and, when the host allows, skip / pause / resume. The
*Playing now* line refreshes every few seconds.

## What travels, and where

- Over Wi-Fi: HTTP `POST /party` on the host phone (port 8773) or `/api/party` on the desktop.
  Traffic never leaves the LAN; the desktop refuses it from outside.
- Over Bluetooth LE: the same JSON, in frames of the negotiated MTU, through GATT service
  `7a2e0c0e-9a6b-4c57-8c3f-00c0ed0ac0d1` (write to `…c0d2`, replies notified on `…c0d3`).
- No server, no account, no analytics. Bluetooth is used for the party only, never for
  location (`neverForLocation`).

The envelope format, signature and ops are in the desktop repository's
`docs/protocol.md` §13. `python -m cued_companion.party` there is a command-line guest for
testing a host.

## Limits

- Bluetooth carries small messages fine (search results are capped at 12) but no audio: the
  host plays from its own library; guests only steer.
- The host's player has to be the phone's own queue; a party cannot drive Android Auto or a
  Cast target.
- A guest sees titles, artists and albums of what it searched for, nothing else of the host's
  library.
