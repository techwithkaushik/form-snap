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

## Import a trained PHOTO/SIGNATURE detector

1. Export the trained detector as a TensorFlow Lite `.tflite` file. Do not select the training ZIP, dataset, or a YOLO model trained for the 80 COCO classes.
2. Open **AI Model** in FormSnap and choose **Import AI Model (.tflite)**. Select the exported model from Downloads or the folder where it was saved.
3. FormSnap validates the tensor layout and runs a small inference smoke test before activating the model. The imported model is copied to app-private storage and stays on the device.
4. Return to the camera screen and point it at a form containing a photo and/or signature. Use the on-screen labels and boxes to judge whether the model actually detects the targets; a successful import alone does not establish detection accuracy.

The YOLO detector path supports a batch-1 RGB image tensor in NHWC or NCHW layout and a raw two-class output with six channels, including common exports such as input `[1,3,640,640]` and output `[1,6,8400]`. The model's class mapping must be **PHOTO = 0** and **SIGNATURE = 1**. Classifier output `[1,2]` is accepted only for whole-frame classification experiments and cannot provide crop coordinates.

The model is kept outside the APK, so importing a custom detector does not increase the installed APK size. Live inference and extraction run locally; representative real-form images and on-device testing are still required before relying on the detector.

## Learning behavior and limitations

Learning stores validated correction metadata, not a trained neural network. Accepted detections can contribute to future correction profiles; a crop produced by the external editor is not automatically used for geometric learning because the editor does not expose source-image coordinates. This avoids teaching incorrect source bounds.

Detection scores are heuristic, not calibrated probabilities. No representative, labelled real-form image corpus is currently included in this repository, so claims about real-world precision/recall, clipping rates, or speed improvements require measurement on a held-out dataset. See [the evaluation protocol](docs/detection-evaluation/README.md).

Perspective rectification is conservative and falls back to the original crop when a reliable quadrilateral cannot be estimated. It should be evaluated against real tilted and unboxed samples before release thresholds are tuned.

## Privacy and test data

Do not commit forms containing real names, faces, signatures, IDs, or other personal information without consent and review. Use synthetic or de-identified fixtures. Keep the held-out evaluation set separate from examples used to tune detector thresholds.
