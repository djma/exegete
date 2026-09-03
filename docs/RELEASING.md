# Releasing

Public builds must use one stable release key. Do not distribute the debug APK: Android
uses a shared development key for it, and users cannot move cleanly to a separately
signed release later.

## One-time setup

Create and protect an Android signing key outside this repository. Add these GitHub
Actions secrets:

- `EXEGETE_SIGNING_KEY`: the base64-encoded keystore file;
- `EXEGETE_KEY_ALIAS`: the key alias;
- `EXEGETE_KEYSTORE_PASSWORD`: the keystore password; and
- `EXEGETE_KEY_PASSWORD`: the key password.

Keep an offline backup. Losing the key prevents installed copies from accepting future
updates.

## Publish

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit the release.
3. Create and push a tag that matches the version, such as `v0.8.0`.

The release workflow runs tests and lint, builds a signed APK, creates a checksum, and
attaches both files to a GitHub prerelease.
