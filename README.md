# FormSnap 2.0

Offline Android app for detecting and preparing photograph and signature crops from a captured or imported form image.

## Product flow

1. Capture with the camera or import an image.
2. Detect photo and signature candidates independently with OpenCV; either, both, or neither may be present.
3. Review separate previews. Accept or reject candidates, or use the crop editor for a manual output adjustment.
4. Save each output to its own user-selected folder. The selected folder permission is persisted across app restarts.
5. Export/import validated learning profiles with a portable `.fsl` backup, or reset local learning memory without removing the built-in detector.

Core processing is local and the Android manifest does not request the `INTERNET` permission. Original source images are not included in learning backups.

## Architecture

- `app/src/main/java/org/techwithkaushik/formsnap/pipeline`: OpenCV candidate detection, crop/normalization, quality checks, validated learning, portable backups and processing services.
- `app/src/main/java/org/techwithkaushik/formsnap/ui`: Compose preview and correction screens.
- `app/src/main/java/org/techwithkaushik/formsnap/foundation`: per-run temporary storage and cleanup.
- `feature/capture`: multiplatform capture feature boundary.
- `docs/detection-evaluation`: labelled-corpus fixture schema and evaluation protocol.

## Build and test

Use the repository's Gradle wrapper and the Java/Android SDK versions configured by the project.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

CI downloads the OpenCV Android SDK before running tests/builds. A successful compile is necessary but not sufficient for a release.

## Output size

The save step encodes JPEG output under the configured per-image KB budget by first trying high JPEG quality and only then reducing dimensions if required. A strict size cap and literally lossless output cannot both be guaranteed for every image. If the configured cap cannot be met above the minimum safe dimensions, saving fails with an explanatory message rather than silently writing an oversized file.

## Learning behavior and limitations

Learning stores validated correction metadata, not a trained neural network. Accepted detections can contribute to future correction profiles; a crop produced by the external editor is not automatically used for geometric learning because the editor does not expose source-image coordinates. This avoids teaching incorrect source bounds.

Detection scores are heuristic, not calibrated probabilities. No representative, labelled real-form image corpus is currently included in this repository, so claims about real-world precision/recall, clipping rates, or speed improvements require measurement on a held-out dataset. See [the evaluation protocol](docs/detection-evaluation/README.md).

Perspective rectification is conservative and falls back to the original crop when a reliable quadrilateral cannot be estimated. It should be evaluated against real tilted and unboxed samples before release thresholds are tuned.

## Privacy and test data

Do not commit forms containing real names, faces, signatures, IDs, or other personal information without consent and review. Use synthetic or de-identified fixtures. Keep the held-out evaluation set separate from examples used to tune detector thresholds.
