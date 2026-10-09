# Bootstrap AI model

The Android build downloads a generic YOLOv8 Nano Float32 TFLite model into generated assets so the build pipeline can verify dependency retrieval and package assembly.

## Important limitation

This bootstrap model is a generic COCO detector. Its classes are ordinary objects such as people, bicycles, and cars. It is **not** trained for FormSnap's two target classes:

- 0 = Photo
- 1 = Signature

The generic model is not a valid FormSnap detector and must not be presented as Photo/Signature recognition. Its output tensor shape is intentionally rejected by the FormSnap two-class model contract. A successful APK build only verifies compilation and packaging; it does not prove that photo/signature detection works.

For production, provide a genuinely trained and evaluated two-class model named `formsnap_yolov8n_int8.tflite`, with raw YOLO output containing four box channels and two class-score channels. Evaluate it on held-out forms before release.

The bootstrap model source is the public `naz23/yolo-tensorflow-lite` repository's `yolov8n_float32.tflite` file. Review its licensing and provenance before shipping a release build. Replace it with a project-owned or properly licensed model for production.
