# Your listening ledger

Side menu → **Your ledger**. Spotify shows you your year once, in December, from its servers.
CUEd has every play in its own log already, so the ledger is there any day, for any period,
computed on the phone.

Pick **This month**, **Last month**, **30 days**, **7 days**, **This year** or **All time**:

- plays, listening time, distinct tracks and artists;
- **new to you**: tracks whose first ever play fell inside the period;
- active days, longest streak, current streak (alive if you listened today or yesterday);
- skip rate (a play that ended before half the track counts as a skip);
- when you listen: plays by hour of day, by weekday, and day by day with the busiest day;
- top artists, tracks, albums and genres with plays and time.

**Export** (share icon) writes a `cued-ledger/1` JSON file with the summary and every play in
the period, and hands it to the share sheet. The desktop client exports the same shape from
its own plays, so the two can be merged by hand or by any script. **Forget** (sweep icon)
deletes the play log; per-track play counts stay.

## What is counted

The player logs a row when a track finishes or is skipped (`play_events`). Listening time is
the track's duration per completed play and a quarter of it per skip, since the log does not
say where the skip happened. Nothing is sent anywhere; the ledger has no network code.
