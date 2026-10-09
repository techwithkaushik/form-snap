# FormSnap AI model

FormSnap's current detector contract contains exactly two classes:

- 0 = Photo
- 1 = Signature

Expected input is a fixed-size, 4D RGB image tensor with batch size 1. The current runtime supports raw two-class YOLO output with six channels: four box values followed by Photo and Signature scores. NMS-formatted outputs and models with different class counts are not accepted.

Place a genuinely trained, compatible model here with the exact name:

formsnap_yolov8n_int8.tflite

The repository intentionally does not contain a fake or placeholder FormSnap model. A generic COCO model is not a Photo/Signature detector. Model tensor validation can reject incompatible files, but tensor shapes alone cannot prove class order or detection accuracy. Validate any trained model on held-out forms before shipping it.

Training data and training must remain on-device/local unless the user explicitly exports a dataset. GitHub Actions must not upload private forms or train models using user data.
