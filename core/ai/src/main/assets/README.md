# FormSnap AI model

Place the trained on-device model here with the exact name:

formsnap_yolov8n_int8.tflite

Required classes:
0 = Photo
1 = Signature
2 = Handwriting

Expected input:
320 x 320 RGB.

The repository intentionally does not contain a fake or placeholder binary model.
The detector fails clearly at runtime until the real trained model is added.
