# Signing

`cued-dev.jks` (alias `cued-dev`, password `cued-dev`) is a **public** key used only to
sign pre-release builds so that they install and upgrade over each other. Anyone can
sign an APK with it, so it proves nothing about who built the APK. Treat pre-release
APKs like any other file you sideload: get them from the GitHub Releases page.

For real releases, add these repository secrets and the release workflow switches to
them automatically (the dev key is then never used):

| Secret | Value |
|---|---|
| `CUED_KEYSTORE_B64` | `base64 -w0 your.jks` |
| `CUED_KEYSTORE_PASSWORD` | keystore password |
| `CUED_KEY_ALIAS` | key alias |
| `CUED_KEY_PASSWORD` | key password |

Generate a private key with:

```bash
keytool -genkeypair -keystore cued-release.jks -alias cued -keyalg RSA -keysize 4096 -validity 10000
```

Keep it out of the repo. Once you ship an APK signed with the private key, users cannot
upgrade from a dev-signed build without uninstalling first (Android's signing rule), so
switch before you hand builds to other people if you can.
