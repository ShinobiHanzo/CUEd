# Bug reports

**Settings → Report a bug**, the **Report** button on a failed download, and the
"CUEd crashed last time" banner on Home all open the same sheet: a one-line
title, a description, and a checkbox to attach the recent debug log.

Where it goes: a public issue on
[github.com/ShinobiHanzo/CUEd/issues](https://github.com/ShinobiHanzo/CUEd/issues),
labelled `bug` and `from-app`. That is where the maintainers (including the
assistant that writes most of this code) read, reproduce and fix things.

Two routes:

| | With a GitHub token (Settings → Updates) | Without |
|---|---|---|
| How | `POST /repos/ShinobiHanzo/CUEd/issues` from the phone | GitHub's new-issue form opens in the browser, prefilled |
| Log attached | last 40 KB, in a collapsible block | last 4 KB in the link (URL length cap); the full report is copied to the clipboard to paste in |
| Account | token needs *Issues: write* (fine-grained) or *public_repo* (classic) | you submit while signed in to GitHub |

What the body contains, in order: your description; the app version, Android
version and device model; the download backend and format; whether crossfade,
tempo-match, silence skipping and the lock-screen player are on; then the log.

What is redacted before anything leaves the phone: GitHub tokens, `Bearer`
headers, 32-hex strings (Spotify client ids and secrets), and `client_secret=`,
`access_token=`, `refresh_token=` query values. "Show what gets sent" in the
sheet previews the log tail after redaction.

Nothing is sent until you press the button. Issues are public: do not paste
anything into the description you would not post on a forum.

## Crashes

The uncaught-exception handler writes `logs/crash.txt` (exception, stack trace,
the last 80 log lines) before the process dies. On the next launch Home shows a
banner; "Tap to report" opens the sheet with the crash prefilled and clears the
marker. This works even with Debug log off.
