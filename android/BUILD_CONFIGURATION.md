# Android build configuration

The Android project is generated on the GitHub runner with Flutter 3.38.5.

The workflow then applies the release-signing configuration to the generated
`android/app/build.gradle.kts`. This is intentional: the development device
is 32-bit Termux and does not build Android locally.

Signing secrets use the same names as the previous student-comparison project:

- `SIGNING_KEYSTORE` — base64 encoded JKS/keystore
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_STORE_PASSWORD`

If the signing secrets are absent, the workflow still produces an unsigned
release APK so CI can validate the Flutter project.
