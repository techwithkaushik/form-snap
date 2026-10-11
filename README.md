# AI ObjectSnap

Offline Android **universal TFLite object detector**. Import a compatible YOLO-style `.tflite` object-detection model, run live detection, inspect class IDs/names and confidence scores, select detected boxes, and crop/save any detected object. The app is not limited to photo/signature classes.

## Product flow

1. Capture with the camera or import an image.
2. Load the active TFLite object-detection model and detect objects in the image.
3. Show each detection's class label, confidence, and bounding box; select detections for cropping or use auto-save.
4. Save each selected detection under its model/class label in the configured output folder.
5. Import a matching labels file when the model does not contain class names; export/import model backups as needed.

Core inference is local and offline. Semantic class names can only be recovered when they are embedded in model metadata or supplied in a matching labels file. If neither exists, the app shows stable generic names such as `Class 0`, `Class 1` instead of `Label missing`; these are class IDs, not guessed object names.

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

## Import a universal TFLite detector

1. Open **AI Model** and select **Import TFLite model (.tflite)**.
2. The app validates the input image tensor, supported YOLO-style detection output, and a real inference smoke test before activating the model.
3. If class names are embedded in TFLite metadata, they are loaded automatically. Otherwise, raw YOLO outputs provide the number of classes, and the app displays generic labels such as `Class 0` and `Class 1`.
4. If you need semantic names such as `person`, `car`, or custom trained labels, import the matching labels file from **Model Details → Import labels**. Labels must be in the model's class-index order; duplicate names are preserved because class indexes must not shift.
5. Return to Live Detection. Each detected object shows its class label/ID, confidence, and bounding box; detected crops are saved using the selected output settings.

The detector supports batch-1 RGB image tensors in NHWC or NCHW layout and raw YOLO tensors with dynamic class counts. Post-NMS outputs are supported where their layout matches the decoder contract. Classification-only models are rejected because they cannot provide object coordinates. Imported models stay in app-private storage and do not increase APK size.

## Learning behavior and limitations

Learning stores validated correction metadata, not a trained neural network. Accepted detections can contribute to future correction profiles; a crop produced by the external editor is not automatically used for geometric learning because the editor does not expose source-image coordinates. This avoids teaching incorrect source bounds.

Detection scores are heuristic, not calibrated probabilities. No representative, labelled real-form image corpus is currently included in this repository, so claims about real-world precision/recall, clipping rates, or speed improvements require measurement on a held-out dataset. See [the evaluation protocol](docs/detection-evaluation/README.md).

Perspective rectification is conservative and falls back to the original crop when a reliable quadrilateral cannot be estimated. It should be evaluated against real tilted and unboxed samples before release thresholds are tuned.

## Privacy and test data

Do not commit forms containing real names, faces, signatures, IDs, or other personal information without consent and review. Use synthetic or de-identified fixtures. Keep the held-out evaluation set separate from examples used to tune detector thresholds.
