# Widgets and the lock-screen player

## Home-screen widget

Long-press the home screen → **Widgets** → **CUEd**, or use **Settings → Lock screen
and widgets → Add widget to home screen** on launchers that support pinning.

One widget, three looks, chosen by how you resize it:

| Size | Shows |
|---|---|
| 1 row (bar) | cover · title · ⏮ ⏯ ⏭ (narrow: play/pause only) |
| 2 rows (card) | cover, title and artist, controls underneath |
| 3+ rows (tall) | big cover with title, artist and controls below |

Tapping the cover or text opens Now Playing. The buttons go through the same
`MediaController` path as the app's own UI, so they also start the playback service
if it was killed; with nothing queued they do nothing and the widget reads "Tap to
open CUEd".

How it stays current: `PlaybackService` listens to the player and, on every track or
play-state change, writes a snapshot (title, artist, playing flag, a 256 px cover) to
the widget's state and asks Glance to re-render. No polling, no `updatePeriodMillis`.
When the service is destroyed it publishes `playing = false` so a stale pause button
never lingers. Cover art comes from MediaStore album art first, then from the file's
own embedded picture.

Code: `app/src/main/kotlin/dev/cued/app/widget/`.

## Lock-screen player

**Settings → Lock screen and widgets → Lock screen player.** Off by default.

With it on, when the screen goes off while music is playing, CUEd starts its own
full-screen player flagged *show when locked*. Press the power button and it is
already there, above the keyguard: a clock, the cover (also blurred as the
background), title and artist, a scrubber and big previous / play / next buttons.

- **Swipe down** hides it until the next screen-off.
- **Unlock and open** asks the keyguard to dismiss (PIN / fingerprint as usual) and
  then opens Now Playing.
- It goes away by itself when you unlock, and when the queue ends.
- It never keeps the screen on and never turns it on.

The stock media controls on the lock screen are untouched and remain the fallback.

### The permission

Android 10 and later only let a background service start an activity when the app
has **Display over other apps** (`SYSTEM_ALERT_WINDOW`). That is the whole reason the
toggle asks for it; CUEd uses it for nothing else. Settings shows an **Allow display
over other apps** button while it is missing, and the gate stays silent until it is
granted. On Android 8 and 9 no permission is needed.

Code: `app/src/main/kotlin/dev/cued/app/lockscreen/` (`LockScreenGate` lives inside
the playback service; `LockScreenActivity` is the screen).
