# FormSnap

Offline Android form capture and photo/signature preparation app.

## Android CI

This project follows the same CI approach used for the previous
`student-comparison` Android project: the GitHub runner installs Flutter
3.38.5, generates the missing Android host files, restores the repository's
custom `android/app/build.gradle.kts`, configures the release keystore from
GitHub secrets, and builds the release APK.

The Android build is intentionally performed on GitHub Actions because the
development device uses 32-bit Termux.

## Signing secrets

Configure these repository secrets when a signed APK is required:

- `SIGNING_KEYSTORE`: base64 encoded JKS/keystore
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_STORE_PASSWORD`

If `SIGNING_KEYSTORE` is not present, the release build falls back to the
debug signing key for CI validation. No keystore is committed to the
repository.

## Current app foundation

- Whole-form capture
- Close photo capture
- Close signature capture
- Existing image selection
- Class 8 2026–27 template
- Photo 40 × 50 mm
- Signature 50 × 20 mm
- 300 DPI target output
- JPEG maximum-KB compression
