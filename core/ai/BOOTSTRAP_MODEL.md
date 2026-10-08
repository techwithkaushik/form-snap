# Bootstrap AI model

FormSnap can build a development APK even before the custom 3-class model exists.

The core:ai module downloads a YOLOv8 Nano Float32 TFLite model during the Gradle build into generated assets. The model is not committed to Git and is packaged into the APK for offline runtime inference.

## Important limitation

This bootstrap model is a generic COCO detector. Its classes are normal object classes such as person, bicycle, car, etc. It is not trained for:

- Photo
- Signature
- Handwriting

Therefore bootstrap rectangles only verify the CameraX -> TFLite -> decoder -> Compose overlay pipeline. They must not be interpreted as Photo/Signature detections.

When the custom FormSnap model is added as formsnap_yolov8n_int8.tflite, the trained model takes precedence over the bootstrap model and the existing 3-class decoder is used:

- 0 Photo
- 1 Signature
- 2 Handwriting

The bootstrap model source is the public naz23/yolo-tensorflow-lite repository's yolov8n_float32.tflite file. Review its licensing/provenance before shipping a release build. Replace it with a project-owned or properly licensed model for production.
