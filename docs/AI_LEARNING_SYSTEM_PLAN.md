# FormSnap AI Learning System — Implementation Plan

**Target branch:** `feature/foundation-pipeline`  
**Runtime:** Kotlin Multiplatform / Android, Jetpack Compose, CameraX, TensorFlow Lite  
**Production detector classes (fixed order):** `0 = PHOTO`, `1 = SIGNATURE`

## Goal

Let users correct detections offline, save reviewed examples locally, export a labeled dataset, train a new detector in a separate training environment, validate the exported TFLite model, and import/activate it safely in FormSnap.

**Important distinction:** storing examples is not model training. The Android app will collect and label data; model training/fine-tuning will run in GitHub Actions or another supported training runner. Do not attempt full YOLO training on the target 4 GB RAM phone.

## Architecture

1. **Live inference** — existing TFLite detector continues to run PHOTO/SIGNATURE inference. It must never silently treat a generic COCO model as the production model.
2. **Correction UI** — user can select a live detection, adjust a crop manually, assign PHOTO or SIGNATURE, or mark a false positive / missed object.
3. **Local example store** — save original/cropped image, normalized bounding box, class ID, timestamp, model fingerprint, and optional confidence. Keep data offline by default.
4. **Dataset export** — package reviewed examples as images plus YOLO labels and `data.yaml`; provide preview/counts before export. Keep private form data out of public repositories.
5. **Training pipeline** — consume a versioned labeled dataset; fine-tune a small pretrained detector; evaluate on a held-out test split; export float32 TFLite first.
6. **Model validation** — check class names/order, tensor shapes, tensor data types, quantization, input normalization, output decoder layout, and a small set of known validation images. Tensor shape alone does not prove model quality.
7. **Safe activation** — import model to a temporary file, validate it, then atomically activate it. Keep the previous known-good model until the new one passes smoke tests; support rollback.
8. **Feedback loop** — users add hard examples, create a new dataset version, retrain, compare metrics and Android smoke tests, then distribute a new model artifact.

## Phases and acceptance criteria

### Phase 1 — Data correction and collection (offline Android)

- Add a `Improve AI` / `Correct detection` flow.
- Support PHOTO, SIGNATURE, missed object, false positive, and discard actions.
- Allow users to resize/move the bounding box before saving.
- Save examples to app-private storage / local database; do not request storage permission just to open the app.
- Record consent/source and let users review/delete collected examples.
- Use quotas and image resizing to protect storage/RAM.
- Tests: class IDs are always 0 or 1; normalized boxes remain in [0,1]; invalid/empty crops are rejected; deletion removes metadata and image bytes.

### Phase 2 — Dataset export

- Export reviewed examples as ZIP with YOLO layout:
  `images/{train,val,test}`, `labels/{train,val,test}`, `data.yaml`.
- Split by source form/document, not by near-identical crops, to reduce leakage.
- Require a minimum number of examples per class and warn if a split lacks either class.
- Include negative examples with empty label files where appropriate.
- Export only on explicit user action; show what is included and warn about sensitive personal information.
- Tests: exported ZIP opens, every image has the matching label file, labels parse, and class order is exact.

### Phase 3 — Training pipeline

- Use the existing manual GitHub Actions workflow and a private versioned dataset source.
- Keep API keys in GitHub Secrets, never in the repository or APK.
- Train a small YOLO detector on a supported runner; hosted CPU training may be slow. Do not assume a GPU is available.
- Evaluate precision, recall, mAP50 and mAP50-95 separately for PHOTO and SIGNATURE on a held-out test set.
- Export float32 TFLite first. Quantization is a later optimization after parity tests.
- Fail the workflow if dataset class order is not exactly PHOTO then SIGNATURE, required splits are missing, metrics/report cannot be produced, or exported tensor contract is incompatible.
- Publish the model and a machine-readable report as workflow artifacts; do not automatically commit model binaries to source control.

### Phase 4 — Model validation and safe import

- Build a shared model-contract report: model hash, input shape/type, normalization, output shapes/types, class map and export version.
- Run representative known images through the exported model and verify boxes/confidence against expected labels.
- Ensure output decoder supports the exact exporter layout; do not infer NMS/raw format from a dimension alone.
- Import to a temporary file; verify interpreter allocation and contract; only then switch active model.
- Preserve last-known-good model and provide rollback.
- Tests: generic COCO `[1,84,2100]` is rejected; valid two-class raw output is accepted; malformed, truncated and incompatible models are rejected without replacing active model.

### Phase 5 — On-device acceptance and iteration

- Smoke-test on the actual low-memory Android device with CameraX.
- Measure inference latency, memory use, battery/thermal behavior and detection quality.
- Test box/no-box forms, tilted pages, B&W photos, photocopies, faded/blue/black signatures, varied sizes and low light.
- Compare the new model to the active model on the same test set before activation.
- Start with a conservative confidence threshold and tune it against validation data rather than guessing.

## Security and privacy

- Form images and signatures may contain personal or biometric information. Collection must be opt-in, local by default, reviewable and deletable.
- Do not upload samples automatically. Dataset export and upload require explicit user action and clear privacy warnings.
- Keep Roboflow/API credentials out of app code, model artifacts, logs and git history.
- Do not put private training examples into public GitHub artifacts.

## First implementation order

1. Implement the local correction/annotation screen and data model.
2. Add export-to-YOLO ZIP and dataset integrity tests.
3. Test the training workflow against a tiny synthetic/non-sensitive labeled dataset.
4. Fix the TFLite exporter/runtime contract and add decoder tests.
5. Add validated model import, activation and rollback.
6. Test the full loop on device before collecting more data.

## Definition of done

A user can correct detections without internet, export reviewed examples, train a new two-class model through the controlled pipeline, inspect evaluation/tensor reports, import a compatible TFLite artifact, and activate or roll back without losing the previous working model. A successful CI build alone does not mean the model is trained or accurate.
