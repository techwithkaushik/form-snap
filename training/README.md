# FormSnap PHOTO / SIGNATURE model training

This pipeline trains a **custom two-class object detector** and exports a TFLite model for FormSnap. The repository's generic/bootstrap model is not a trained PHOTO/SIGNATURE model.

## 1. Annotate your existing form images

1. Create a private project on [Roboflow](https://roboflow.com/). Form images can contain names, signatures, photos, Aadhaar numbers, phone numbers, or other personal information. Prefer redacted/synthetic examples and do not upload sensitive forms to a public project.
2. Create an **Object Detection** project.
3. Add exactly these classes, in this order:
   - `PHOTO` (class ID 0)
   - `SIGNATURE` (class ID 1)
4. Upload your form images.
5. Draw tight boxes only around objects that are actually present. PHOTO and SIGNATURE are independent classes: include PHOTO-only images, SIGNATURE-only images, images with both, and negative images containing neither (with an empty YOLO label file). Never invent a box for a missing class. Do not label the full form, printed labels like "Photo", blank signature lines, or table borders.
6. Include varied real cases: color and black-and-white photos, photocopies, tilted forms, different photo aspect ratios, faint/dark/blue signatures, boxed and unboxed areas, and different lighting/distance. Ensure permission to use every image.
7. Generate a dataset version in **YOLOv8** format with separate **train, valid, and test** splits. Keep near-duplicate scans of the same form in the same split to prevent leakage. Ensure PHOTO and SIGNATURE each occur in every split overall, but do not require both classes in every individual image. Include hard negative examples such as blank signature lines, printed photo captions, borders, and forms where one or both targets are absent. Aim for at least hundreds of varied examples to start; quality and diversity matter more than raw count.
8. Confirm the exported class order is exactly `PHOTO`, `SIGNATURE`. Create a new version after any label/class-order changes.

## 2. Configure GitHub Actions

In the repository settings for `techwithkaushik/form-snap`:

**Settings → Secrets and variables → Actions → Secrets**
- `ROBOFLOW_API_KEY`: your Roboflow private API key. Never commit this value.

**Settings → Secrets and variables → Actions → Variables**
- `ROBOFLOW_WORKSPACE`: workspace slug
- `ROBOFLOW_PROJECT`: object-detection project slug
- `ROBOFLOW_VERSION`: integer dataset version

Use a private Roboflow project. The workflow downloads the dataset at run time; the dataset and API key are not committed into this repository.

## 3. Run training

Open **Actions → Train FormSnap PHOTO/SIGNATURE AI → Run workflow** on branch `feature/foundation-pipeline`. Start with the default 100 epochs, 320 input size, batch 8, CPU. GitHub-hosted runners do not guarantee a GPU, so training can be slow and may hit runner time limits for large datasets. For larger jobs, train on a GPU runner/Colab and use the same validation/export checks.

The workflow will:
- reject missing train/valid/test splits or incorrect class order;
- fine-tune pretrained YOLOv8n on the labeled dataset;
- evaluate the best checkpoint on the held-out test split;
- export float32 TFLite first;
- check input/output tensor shapes against the current FormSnap decoder;
- upload the model, test metrics, and tensor report as a workflow artifact.

## 4. Install in FormSnap

Download the workflow artifact `formsnap-photo-signature-tflite` and inspect `model_report.json` before importing the model into the app. Import `formsnap_photo_signature_yolov8n_float32.tflite` using the app's model importer.

**Important compatibility note:** tensor shape is necessary but not sufficient. The current Android detector normalizes pixels to 0..1 and expects a raw two-class YOLO output shaped `[1, 6, N]` or `[1, N, 6]`. The workflow fails if those shape expectations are not met. Validate actual detection boxes and confidence scores on representative forms on the target Android device before replacing the active model. Do not assume a successful build/export means the model is accurate.

## 5. Improve the model

Review per-class precision/recall and false positives on the held-out test set. Add hard examples where photo/signature detection fails, annotate them carefully, create a new dataset version, and retrain. Do not use the test set as training data. Try INT8 only after float32 output is confirmed correct on-device.
