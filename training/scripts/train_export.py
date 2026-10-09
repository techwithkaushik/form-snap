#!/usr/bin/env python3
"""Train a two-class FormSnap detector and export a decoder-compatible TFLite model."""

from __future__ import annotations

import json
import os
import shutil
from pathlib import Path

from roboflow import Roboflow
from ultralytics import YOLO

ROOT = Path.cwd()
WORK = ROOT / "training" / "work"
DATASET_DIR = WORK / "dataset"
RUNS_DIR = WORK / "runs"
EXPORT_DIR = ROOT / "training" / "output"
MODEL_SIZE = int(os.getenv("FORMSNAP_IMAGE_SIZE", "320"))
EPOCHS = int(os.getenv("FORMSNAP_EPOCHS", "100"))
BATCH = int(os.getenv("FORMSNAP_BATCH", "8"))
EXPECTED_CLASSES = ["PHOTO", "SIGNATURE"]


def require_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required GitHub secret/variable: {name}")
    return value


def normalize_names(names: object) -> list[str]:
    if isinstance(names, dict):
        values = [names[key] for key in sorted(names, key=lambda item: int(item))]
    elif isinstance(names, list):
        values = names
    else:
        raise RuntimeError(f"Dataset YAML 'names' must be a list or mapping, got {type(names).__name__}")
    return [str(value).strip().upper() for value in values]


def main() -> None:
    api_key = require_env("ROBOFLOW_API_KEY")
    workspace_name = require_env("ROBOFLOW_WORKSPACE")
    project_name = require_env("ROBOFLOW_PROJECT")
    version_number = int(require_env("ROBOFLOW_VERSION"))

    WORK.mkdir(parents=True, exist_ok=True)
    EXPORT_DIR.mkdir(parents=True, exist_ok=True)

    print("Downloading labeled YOLO dataset from Roboflow...")
    rf = Roboflow(api_key=api_key)
    project = rf.workspace(workspace_name).project(project_name)
    dataset = project.version(version_number).download(
        "yolov8",
        location=str(DATASET_DIR),
        overwrite=True,
    )
    data_yaml = Path(dataset.location) / "data.yaml"
    if not data_yaml.is_file():
        raise RuntimeError(f"Roboflow export did not contain data.yaml at {data_yaml}")

    import yaml

    data = yaml.safe_load(data_yaml.read_text(encoding="utf-8"))
    class_names = normalize_names(data.get("names"))
    if class_names != EXPECTED_CLASSES:
        raise RuntimeError(
            "Class order must be exactly PHOTO then SIGNATURE. "
            f"Expected {EXPECTED_CLASSES}, got {class_names}. "
            "Fix the Roboflow project class order and create a new dataset version."
        )

    for split in ("train", "val", "test"):
        if not data.get(split):
            raise RuntimeError(
                f"Dataset is missing '{split}' split. Export a dataset with train, valid, and test images."
            )

    print(f"Dataset: {data_yaml}")
    print(f"Classes: {class_names}; image size: {MODEL_SIZE}; epochs: {EPOCHS}; batch: {BATCH}")

    # This is only a pretrained starting point. It is NOT the final FormSnap model.
    model = YOLO("yolov8n.pt")
    model.train(
        data=str(data_yaml),
        imgsz=MODEL_SIZE,
        epochs=EPOCHS,
        batch=BATCH,
        workers=2,
        device="cpu",
        patience=20,
        seed=42,
        deterministic=True,
        project=str(RUNS_DIR),
        name="formsnap-photo-signature",
        exist_ok=True,
        plots=True,
        verbose=True,
    )

    # Ultralytics stores the actual run directory on the trainer; train() returns metrics.
    run_dir = Path(model.trainer.save_dir)
    best_weights = run_dir / "weights" / "best.pt"
    if not best_weights.is_file():
        raise RuntimeError(f"Training did not produce best.pt: {best_weights}")

    best = YOLO(str(best_weights))
    metrics = best.val(
        data=str(data_yaml),
        split="test",
        imgsz=MODEL_SIZE,
        batch=1,
        device="cpu",
        plots=True,
    )

    # Float32 first: validate the model before attempting quantization.
    exported = best.export(
        format="tflite",
        imgsz=MODEL_SIZE,
        int8=False,
        nms=False,
        batch=1,
    )
    exported_path = Path(str(exported))
    candidates = [exported_path] if exported_path.is_file() else list(exported_path.rglob("*.tflite"))
    if not candidates:
        candidates = list(run_dir.rglob("*.tflite"))
    if not candidates:
        raise RuntimeError("Ultralytics export finished without producing a .tflite file.")

    candidates.sort(key=lambda path: ("float32" not in path.name.lower(), len(path.name)))
    source_model = candidates[0]
    destination = EXPORT_DIR / "formsnap_photo_signature_yolov8n_float32.tflite"
    shutil.copy2(source_model, destination)

    # Inspect the exported tensor contract expected by FormSnap's current decoder.
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_path=str(destination), num_threads=2)
    interpreter.allocate_tensors()
    input_tensor = interpreter.get_input_details()[0]
    output_tensor = interpreter.get_output_details()[0]
    input_shape = [int(value) for value in input_tensor["shape"]]
    output_shape = [int(value) for value in output_tensor["shape"]]
    input_dtype = str(input_tensor["dtype"])
    output_dtype = str(output_tensor["dtype"])

    if len(input_shape) != 4 or not (
        (input_shape[1] == MODEL_SIZE and input_shape[2] == MODEL_SIZE and input_shape[3] == 3)
        or (input_shape[1] == 3 and input_shape[2] == MODEL_SIZE and input_shape[3] == MODEL_SIZE)
    ):
        raise RuntimeError(f"Unsupported TFLite input shape for FormSnap: {input_shape}")

    channels_first = len(output_shape) >= 3 and output_shape[-2] == 6 and output_shape[-1] > 6
    channels_last = len(output_shape) >= 3 and output_shape[-1] == 6 and output_shape[-2] > 256
    if not (channels_first or channels_last):
        raise RuntimeError(
            f"TFLite output shape {output_shape} does not match FormSnap's current "
            "two-class raw YOLO decoder ([1, 6, N] or [1, N, 6] with more than 256 candidates)."
        )

    report = {
        "classes": {"0": "PHOTO", "1": "SIGNATURE"},
        "input_shape": input_shape,
        "input_dtype": input_dtype,
        "input_quantization": list(input_tensor.get("quantization", (0.0, 0))),
        "output_shape": output_shape,
        "output_dtype": output_dtype,
        "output_quantization": list(output_tensor.get("quantization", (0.0, 0))),
        "image_size": MODEL_SIZE,
        "best_weights": str(best_weights),
        "tflite_model": destination.name,
        "test_metrics": {key: float(value) for key, value in metrics.results_dict.items()},
        "warning": "Successful export and tensor-shape validation do not guarantee detection quality; review test metrics and test real forms on Android.",
    }
    (EXPORT_DIR / "model_report.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, indent=2, ensure_ascii=False))
    print(f"SUCCESS: {destination}")


if __name__ == "__main__":
    main()
