# FormSnap on-device AI training: implementation contract

## Product requirement

The production detector has exactly two classes:

- `0 = PHOTO`
- `1 = SIGNATURE`

Training data, annotations, checkpoints, training, validation, and activation must remain on the Android device. No form image, annotation, model checkpoint, or trained model may be uploaded automatically. GitHub Actions is only for source checks, tests, and APK builds.

## Current state (do not overstate)

The app currently has an offline example store and manual bounding-box annotation/export. The active YOLOv8 TFLite interpreter is an inference runtime; a normal inference-only YOLO export does not include the gradient/loss/optimizer path required to fine-tune its detector weights on the phone. Therefore, collecting examples does not yet train the live detector.

## Required training architecture

Before exposing a Train button as functional, implement a trainable model/runtime specifically designed for Android:

1. **Dataset preparation:** read app-private examples and normalized PHOTO/SIGNATURE boxes, apply EXIF orientation consistently, and produce deterministic train/validation splits that keep both classes represented where the dataset size permits.
2. **Trainable graph:** provide a compact detector with explicit training/loss/optimizer and checkpoint-save/restore operations supported on Android. Do not attempt to train the current inference-only YOLOv8 TFLite file by simply calling `Interpreter.run()`.
3. **Resource guardrails:** run work off the UI thread; serialize training; use small batches, bounded image dimensions, cancellation, progress reporting, and conservative memory limits for low-RAM devices. If the device cannot safely run training, explain why and do not pretend success.
4. **Validation gate:** evaluate the candidate against held-out local examples and check finite loss, class mapping, input/output tensor contract, and usable detections. A tensor shape check alone is not proof of accuracy.
5. **Atomic activation:** write the candidate to a temporary file, reopen and validate it, then atomically switch the active model. Preserve the previous working model for rollback if validation or inference fails.
6. **Privacy:** never request a network upload for learning. Any dataset export must remain a user-initiated optional action and separate from training.
7. **Honest UI states:** distinguish “examples collected”, “training unsupported/not configured”, “training in progress”, “validation failed”, and “model activated”. Do not label annotation or ZIP export as training.

## Acceptance criteria

- Airplane-mode training works after all required runtime assets are installed.
- The training UI works without Roboflow, API keys, or cloud credentials.
- PHOTO and SIGNATURE class IDs remain stable.
- Training can be cancelled without corrupting the active model.
- Low-memory failure leaves the current model intact and reports a useful error.
- CI tests the data preparation, box transforms, class mapping, validation gate, and atomic activation behavior.
- Real-world accuracy must be assessed on held-out forms with boxes and without boxes; successful compilation/export alone is not an accuracy claim.

## Implementation note

Do not wire the existing Ultralytics `train_export.py` script into a device button: it depends on Python/Ultralytics and remote dataset access and is not an Android training runtime. The next implementation step is to select and integrate a detector architecture that genuinely supports local gradient-based training, then implement its Android training backend and checkpoint lifecycle before advertising on-device detector training as available.
