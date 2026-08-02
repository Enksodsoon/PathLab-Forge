"""Leakage-safe BRACS transfer-learning training and evaluation."""

from __future__ import annotations

import csv
import json
import math
import platform
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import scipy.optimize
import sklearn
import torch
import torchvision
from PIL import Image
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from torch import nn
from torch.utils.data import DataLoader, Dataset

from .core import (
    COARSE_GROUP,
    LABEL_TO_INDEX,
    LABELS,
    WEIGHTS_ID,
    MobileNetFeatureEncoder,
    choose_review_threshold,
    group_view_logits,
    metrics_from_probabilities,
    patient_bootstrap_interval,
    read_jsonl,
    sha256,
    softmax,
    tensor_transform,
)
from .wsi_bags import ENCODER_ID as KAIKO_ENCODER_ID
from .wsi_bags import ENCODER_LICENSE as KAIKO_ENCODER_LICENSE
from .wsi_bags import ENCODER_SOURCE as KAIKO_ENCODER_SOURCE
from .wsi_bags import load_kaiko_encoder


@dataclass(frozen=True)
class TrainingConfig:
    views_root: Path
    output_root: Path
    batch_size: int = 64
    threads: int = 6
    bootstrap_iterations: int = 500
    seed: int = 20260802
    encoder: str = "mobilenet"


class ViewDataset(Dataset[tuple[torch.Tensor, int]]):
    def __init__(self, paths: list[Path], transform: Any) -> None:
        self.paths = paths
        self.transform = transform

    def __len__(self) -> int:
        return len(self.paths)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, int]:
        with Image.open(self.paths[index]) as image:
            return self.transform(image.convert("RGB")), index


class FusionFeatureEncoder(nn.Module):
    """Complementary ImageNet and pathology encoders with correct normalization."""

    def __init__(self) -> None:
        super().__init__()
        self.mobilenet = MobileNetFeatureEncoder(pretrained=True).eval()
        self.kaiko = load_kaiko_encoder().eval()
        self.register_buffer(
            "imagenet_mean", torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1)
        )
        self.register_buffer(
            "imagenet_std", torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1)
        )

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        mobile = self.mobilenet((inputs - self.imagenet_mean) / self.imagenet_std)
        kaiko = self.kaiko((inputs - 0.5) / 0.5)
        return torch.cat((mobile, kaiko), dim=1)


def train_bracs_model(config: TrainingConfig) -> dict[str, Any]:
    started = time.perf_counter()
    views_root = config.views_root.resolve()
    output_root = config.output_root.resolve()
    records = read_jsonl(views_root / "views.jsonl")
    _validate_records(records)
    output_root.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(config.seed)
    np.random.seed(config.seed)
    torch.set_num_threads(max(1, config.threads))
    torch.set_num_interop_threads(1)

    feature_data = _features(records, views_root, output_root, config)
    train_mask = feature_data["split"] == "train"
    val_mask = feature_data["split"] == "val"
    test_mask = feature_data["split"] == "test"
    scaler = StandardScaler().fit(feature_data["features"][train_mask])
    scaled = scaler.transform(feature_data["features"])

    selection: list[dict[str, float]] = []
    best_classifier: LogisticRegression | None = None
    best_score = -math.inf
    for regularization in (0.01, 0.1, 1.0, 10.0):
        classifier = _fit_classifier(
            scaled[train_mask],
            feature_data["label_index"][train_mask],
            regularization,
            config.seed,
        )
        logits, labels, _, _ = group_view_logits(
            classifier.decision_function(scaled[val_mask]),
            feature_data["roi_id"][val_mask],
            feature_data["label_index"][val_mask],
            feature_data["patient_id"][val_mask],
        )
        metrics = metrics_from_probabilities(labels, softmax(logits))
        selection.append(
            {"C": regularization, "validation_macro_f1": metrics["macro_f1"]}
        )
        if metrics["macro_f1"] > best_score:
            best_score = float(metrics["macro_f1"])
            best_classifier = classifier
    assert best_classifier is not None

    val_logits, val_labels, _, _ = group_view_logits(
        best_classifier.decision_function(scaled[val_mask]),
        feature_data["roi_id"][val_mask],
        feature_data["label_index"][val_mask],
        feature_data["patient_id"][val_mask],
    )
    temperature = _fit_temperature(val_logits, val_labels)
    val_probabilities = softmax(val_logits, temperature)
    review = choose_review_threshold(val_labels, val_probabilities)

    test_logits, test_labels, test_patients, test_roi_ids = group_view_logits(
        best_classifier.decision_function(scaled[test_mask]),
        feature_data["roi_id"][test_mask],
        feature_data["label_index"][test_mask],
        feature_data["patient_id"][test_mask],
    )
    test_probabilities = softmax(test_logits, temperature)
    test_metrics = metrics_from_probabilities(test_labels, test_probabilities)
    test_metrics["patient_bootstrap_95_ci"] = patient_bootstrap_interval(
        test_labels,
        test_probabilities,
        test_patients,
        iterations=config.bootstrap_iterations,
        seed=config.seed,
    )
    test_metrics["selective"] = _selective_metrics(
        test_labels, test_probabilities, float(review["threshold"])
    )

    baselines = _baselines(feature_data, train_mask, val_mask, test_mask, config.seed)
    artifact_name = {
        "mobilenet": "pathlab_bracs_mobilenet_v1.ts",
        KAIKO_ENCODER_ID: "pathlab_bracs_kaiko_vits16_v1.ts",
        "fusion": "pathlab_bracs_fusion_v1.ts",
    }[config.encoder]
    artifact_path = output_root / artifact_name
    _export_model(best_classifier, scaler, artifact_path, config.encoder)
    predictions_path = output_root / "test_predictions.csv"
    _write_predictions(
        predictions_path,
        test_roi_ids,
        test_patients,
        test_labels,
        test_probabilities,
        float(review["threshold"]),
    )
    _write_confusion(
        output_root / "confusion_matrix.csv", test_metrics["confusion_matrix"]
    )

    result: dict[str, Any] = {
        "schema_version": 1,
        "model_name": _model_name(config.encoder),
        "task": "seven-class breast pathology ROI classification",
        "labels": list(LABELS),
        "coarse_groups": COARSE_GROUP,
        "encoder": _encoder_source(config.encoder),
        "encoder_license": _encoder_license(config.encoder),
        "classifier": "class-balanced multinomial logistic regression",
        "selected_C": float(best_classifier.C),
        "temperature": temperature,
        "review_threshold": review,
        "validation_selection": selection,
        "test": test_metrics,
        "baselines": baselines,
        "splits": {
            split: {
                "rois": len(
                    {record["roi_id"] for record in records if record["split"] == split}
                ),
                "patients": len(
                    {
                        record["patient_id"]
                        for record in records
                        if record["split"] == split
                    }
                ),
            }
            for split in ("train", "val", "test")
        },
        "dataset_artifacts": {
            "views_manifest": "views.jsonl",
            "views_manifest_sha256": sha256(views_root / "views.jsonl"),
            "view_config_sha256": sha256(views_root / "view_config.json"),
        },
        "runtime": {
            "python": platform.python_version(),
            "torch": torch.__version__,
            "torchvision": torchvision.__version__,
            "scikit_learn": sklearn.__version__,
            "device": "cpu",
            "threads": config.threads,
            "seconds": time.perf_counter() - started,
        },
        "artifacts": {
            "torchscript": artifact_path.name,
            "torchscript_sha256": sha256(artifact_path),
            "test_predictions": predictions_path.name,
            "feature_cache": f"feature_cache_{config.encoder}.npz",
        },
        "intended_use": "research and education; not for clinical diagnosis",
        "test_split_touched_once_after_validation_selection": True,
    }
    (output_root / "evaluation.json").write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    model_config = {
        "schema_version": 1,
        "model_name": result["model_name"],
        "labels": list(LABELS),
        "coarse_groups": COARSE_GROUP,
        "temperature": temperature,
        "review_threshold": float(review["threshold"]),
        "review_policy": review,
        "image_size": 224,
        "normalization": _normalization(config.encoder),
        "torchscript": artifact_path.name,
        "torchscript_sha256": sha256(artifact_path),
        "views_manifest_sha256": result["dataset_artifacts"]["views_manifest_sha256"],
        "intended_use": result["intended_use"],
    }
    (output_root / "model_config.json").write_text(
        json.dumps(model_config, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    joblib.dump(
        {"scaler": scaler, "classifier": best_classifier, "labels": LABELS},
        output_root / "research_classifier.joblib",
    )
    (output_root / "TRAINING_REPORT.md").write_text(_report(result), encoding="utf-8")
    return result


def _validate_records(records: list[dict[str, Any]]) -> None:
    if len(records) != 4_539:
        raise ValueError(f"expected 4,539 view records, found {len(records)}")
    patients: dict[str, set[str]] = {split: set() for split in ("train", "val", "test")}
    for record in records:
        if record["label"] not in LABEL_TO_INDEX:
            raise ValueError(f"unknown label: {record['label']}")
        patients[record["split"]].add(str(record["patient_id"]))
    for left, right in (("train", "val"), ("train", "test"), ("val", "test")):
        if patients[left] & patients[right]:
            raise ValueError(f"patient leakage between {left} and {right}")


def _features(
    records: list[dict[str, Any]],
    views_root: Path,
    output_root: Path,
    config: TrainingConfig,
) -> dict[str, np.ndarray]:
    cache = output_root / f"feature_cache_{config.encoder}.npz"
    views_hash = sha256(views_root / "views.jsonl")
    if cache.is_file():
        loaded = np.load(cache, allow_pickle=False)
        if str(loaded["views_manifest_sha256"].item()) == views_hash:
            return {
                name: loaded[name]
                for name in loaded.files
                if name != "views_manifest_sha256"
            }
    paths: list[Path] = []
    roi_ids: list[str] = []
    patient_ids: list[str] = []
    splits: list[str] = []
    labels: list[int] = []
    views: list[str] = []
    for record in records:
        for view_name in ("global", "center"):
            paths.append(views_root / record[f"{view_name}_path"])
            roi_ids.append(record["roi_id"])
            patient_ids.append(str(record["patient_id"]))
            splits.append(record["split"])
            labels.append(LABEL_TO_INDEX[record["label"]])
            views.append(view_name)
    encoder, feature_dimension, transform = _encoder(config.encoder)
    for parameter in encoder.parameters():
        parameter.requires_grad_(False)
    loader = DataLoader(
        ViewDataset(paths, transform),
        batch_size=config.batch_size,
        shuffle=False,
        num_workers=0,
    )
    features = np.empty((len(paths), feature_dimension), dtype=np.float32)
    with torch.inference_mode():
        for batch, indices in loader:
            values = encoder(batch).cpu().numpy().astype(np.float32, copy=False)
            features[indices.numpy()] = values
    data = {
        "features": features,
        "roi_id": np.asarray(roi_ids),
        "patient_id": np.asarray(patient_ids),
        "split": np.asarray(splits),
        "label_index": np.asarray(labels, dtype=np.int64),
        "view": np.asarray(views),
        "view_path": np.asarray([str(path) for path in paths]),
    }
    np.savez_compressed(cache, views_manifest_sha256=views_hash, **data)
    return data


def _fit_classifier(
    features: np.ndarray, labels: np.ndarray, regularization: float, seed: int
) -> LogisticRegression:
    return LogisticRegression(
        C=regularization,
        class_weight="balanced",
        max_iter=2_000,
        random_state=seed,
        solver="lbfgs",
    ).fit(features, labels)


def _fit_temperature(logits: np.ndarray, labels: np.ndarray) -> float:
    def objective(log_temperature: float) -> float:
        return float(
            -np.log(
                softmax(logits, math.exp(log_temperature))[
                    np.arange(len(labels)), labels
                ]
                + 1e-12
            ).mean()
        )

    result = scipy.optimize.minimize_scalar(
        objective, bounds=(-3.0, 3.0), method="bounded"
    )
    return float(math.exp(result.x))


def _color_features(paths: list[Path]) -> np.ndarray:
    rows: list[np.ndarray] = []
    for path in paths:
        with Image.open(path) as image:
            pixels = np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0
        channel_features: list[float] = []
        for channel in range(3):
            values = pixels[:, :, channel]
            histogram, _ = np.histogram(values, bins=16, range=(0.0, 1.0), density=True)
            channel_features.extend((float(values.mean()), float(values.std())))
            channel_features.extend(histogram.astype(float))
        rows.append(np.asarray(channel_features, dtype=np.float32))
    return np.vstack(rows)


def _baselines(
    data: dict[str, np.ndarray],
    train_mask: np.ndarray,
    val_mask: np.ndarray,
    test_mask: np.ndarray,
    seed: int,
) -> dict[str, Any]:
    majority = int(np.bincount(data["label_index"][train_mask]).argmax())
    test_labels = group_view_logits(
        np.zeros((int(test_mask.sum()), len(LABELS))),
        data["roi_id"][test_mask],
        data["label_index"][test_mask],
        data["patient_id"][test_mask],
    )[1]
    majority_probabilities = np.full((len(test_labels), len(LABELS)), 1e-6)
    majority_probabilities[:, majority] = 1.0 - (len(LABELS) - 1) * 1e-6

    color = _color_features([Path(value) for value in data["view_path"].astype(str)])
    color_scaler = StandardScaler().fit(color[train_mask])
    scaled_color = color_scaler.transform(color)
    best_color: LogisticRegression | None = None
    best_score = -math.inf
    selection: list[dict[str, float]] = []
    for regularization in (0.01, 0.1, 1.0, 10.0):
        candidate = _fit_classifier(
            scaled_color[train_mask],
            data["label_index"][train_mask],
            regularization,
            seed,
        )
        val_logits, val_labels, _, _ = group_view_logits(
            candidate.decision_function(scaled_color[val_mask]),
            data["roi_id"][val_mask],
            data["label_index"][val_mask],
            data["patient_id"][val_mask],
        )
        score = float(
            metrics_from_probabilities(val_labels, softmax(val_logits))["macro_f1"]
        )
        selection.append({"C": regularization, "validation_macro_f1": score})
        if score > best_score:
            best_score = score
            best_color = candidate
    assert best_color is not None
    color_logits, color_labels, _, _ = group_view_logits(
        best_color.decision_function(scaled_color[test_mask]),
        data["roi_id"][test_mask],
        data["label_index"][test_mask],
        data["patient_id"][test_mask],
    )
    return {
        "majority_class": {
            "label": LABELS[majority],
            **metrics_from_probabilities(test_labels, majority_probabilities),
        },
        "color_histogram_logistic": {
            "selected_C": float(best_color.C),
            "validation_selection": selection,
            **metrics_from_probabilities(color_labels, softmax(color_logits)),
        },
    }


def _export_model(
    classifier: LogisticRegression,
    scaler: StandardScaler,
    artifact_path: Path,
    encoder_name: str,
) -> None:
    encoder, feature_dimension, _ = _encoder(encoder_name)
    model = nn.Sequential(encoder, nn.Linear(feature_dimension, len(LABELS))).eval()
    coefficient = (
        classifier.coef_.astype(np.float32) / scaler.scale_.astype(np.float32)[None, :]
    )
    bias = classifier.intercept_.astype(np.float32) - (
        classifier.coef_.astype(np.float32)
        * scaler.mean_.astype(np.float32)[None, :]
        / scaler.scale_.astype(np.float32)[None, :]
    ).sum(axis=1)
    with torch.no_grad():
        model[1].weight.copy_(torch.from_numpy(coefficient))
        model[1].bias.copy_(torch.from_numpy(bias))
    traced = torch.jit.trace(model, torch.zeros((1, 3, 224, 224), dtype=torch.float32))
    torch.jit.save(traced, artifact_path)


def _encoder(name: str) -> tuple[nn.Module, int, Any]:
    if name == "mobilenet":
        return MobileNetFeatureEncoder(pretrained=True).eval(), 1024, tensor_transform()
    if name == KAIKO_ENCODER_ID:
        return (
            load_kaiko_encoder().eval(),
            384,
            tensor_transform((0.5, 0.5, 0.5), (0.5, 0.5, 0.5)),
        )
    if name == "fusion":
        return (
            FusionFeatureEncoder().eval(),
            1408,
            tensor_transform((0.0, 0.0, 0.0), (1.0, 1.0, 1.0)),
        )
    raise ValueError(f"unsupported encoder: {name}")


def _normalization(name: str) -> dict[str, list[float]]:
    if name == KAIKO_ENCODER_ID:
        return {"mean": [0.5, 0.5, 0.5], "std": [0.5, 0.5, 0.5]}
    if name == "fusion":
        return {"mean": [0.0, 0.0, 0.0], "std": [1.0, 1.0, 1.0]}
    return {
        "mean": [0.485, 0.456, 0.406],
        "std": [0.229, 0.224, 0.225],
    }


def _model_name(name: str) -> str:
    if name == KAIKO_ENCODER_ID:
        return "PathLab BRACS Kaiko ViT-S/16 Transfer v1"
    if name == "fusion":
        return "PathLab BRACS Pathology-ImageNet Fusion v1"
    return "PathLab BRACS MobileNetV3-Small Transfer v1"


def _encoder_source(name: str) -> str:
    if name == KAIKO_ENCODER_ID:
        return KAIKO_ENCODER_SOURCE
    if name == "fusion":
        return f"{WEIGHTS_ID} + {KAIKO_ENCODER_SOURCE}"
    return WEIGHTS_ID


def _encoder_license(name: str) -> str:
    if name == KAIKO_ENCODER_ID:
        return KAIKO_ENCODER_LICENSE
    if name == "fusion":
        return "Torchvision BSD-3-Clause and Kaiko non-commercial research license"
    return "Torchvision BSD-3-Clause; ImageNet weights terms apply"


def _selective_metrics(
    labels: np.ndarray, probabilities: np.ndarray, threshold: float
) -> dict[str, float | int]:
    confidence = probabilities.max(axis=1)
    selected = confidence >= threshold
    predictions = probabilities.argmax(axis=1)
    return {
        "threshold": threshold,
        "accepted": int(selected.sum()),
        "reviewed": int((~selected).sum()),
        "coverage": float(selected.mean()),
        "accepted_accuracy": float((predictions[selected] == labels[selected]).mean())
        if selected.any()
        else 0.0,
    }


def _write_predictions(
    path: Path,
    roi_ids: np.ndarray,
    patients: np.ndarray,
    labels: np.ndarray,
    probabilities: np.ndarray,
    threshold: float,
) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        fields = [
            "roi_id",
            "patient_id",
            "truth",
            "prediction",
            "confidence",
            "needs_review",
            *[f"p_{label}" for label in LABELS],
        ]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for roi_id, patient, truth, row in zip(
            roi_ids, patients, labels, probabilities
        ):
            prediction = int(row.argmax())
            confidence = float(row[prediction])
            writer.writerow(
                {
                    "roi_id": roi_id,
                    "patient_id": patient,
                    "truth": LABELS[int(truth)],
                    "prediction": LABELS[prediction],
                    "confidence": f"{confidence:.8f}",
                    "needs_review": str(confidence < threshold).lower(),
                    **{
                        f"p_{label}": f"{float(row[index]):.8f}"
                        for index, label in enumerate(LABELS)
                    },
                }
            )


def _write_confusion(path: Path, matrix: list[list[int]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["truth/prediction", *LABELS])
        for label, row in zip(LABELS, matrix):
            writer.writerow([label, *row])


def _report(result: dict[str, Any]) -> str:
    test = result["test"]
    baseline = result["baselines"]["majority_class"]
    return f"""# PathLab BRACS model training report

- Model: {result["model_name"]}
- Encoder: {result["encoder"]}
- Fine accuracy: {test["accuracy"]:.4f}
- Fine macro F1: {test["macro_f1"]:.4f}
- Fine balanced accuracy: {test["balanced_accuracy"]:.4f}
- Top-2 accuracy: {test["top2_accuracy"]:.4f}
- Coarse BT/AT/MT accuracy: {test["coarse_accuracy"]:.4f}
- Coarse macro F1: {test["coarse_macro_f1"]:.4f}
- Majority baseline accuracy: {baseline["accuracy"]:.4f}
- Test ROIs: {result["splits"]["test"]["rois"]}
- Test patients: {result["splits"]["test"]["patients"]}
- Low-confidence review threshold: {result["review_threshold"]["threshold"]:.6f}

The official patient-disjoint test split was used only after validation-based
regularization selection and temperature calibration. This artifact is for
research and education, not clinical diagnosis.
"""
