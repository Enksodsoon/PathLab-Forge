"""Shared model, preprocessing, grouping, and metric primitives."""

from __future__ import annotations

import hashlib
import json
from collections import defaultdict
from collections.abc import Iterable
from pathlib import Path
from typing import Any

import numpy as np
import torch
from PIL import Image, ImageOps
from sklearn.metrics import (
    accuracy_score,
    balanced_accuracy_score,
    confusion_matrix,
    f1_score,
    log_loss,
    precision_recall_fscore_support,
)
from torch import nn
from torchvision.models import MobileNet_V3_Small_Weights, mobilenet_v3_small
from torchvision.transforms import v2

LABELS = ("N", "PB", "UDH", "FEA", "ADH", "DCIS", "IC")
LABEL_TO_INDEX = {label: index for index, label in enumerate(LABELS)}
COARSE_GROUP = {
    "N": "BT",
    "PB": "BT",
    "UDH": "BT",
    "FEA": "AT",
    "ADH": "AT",
    "DCIS": "MT",
    "IC": "MT",
}
COARSE_LABELS = ("BT", "AT", "MT")
WEIGHTS_ID = "MobileNet_V3_Small_Weights.IMAGENET1K_V1"
IMAGE_SIZE = 224


class MobileNetFeatureEncoder(nn.Module):
    """ImageNet-pretrained MobileNetV3-Small through its 1024-D penultimate layer."""

    def __init__(self, *, pretrained: bool = True) -> None:
        super().__init__()
        weights = MobileNet_V3_Small_Weights.DEFAULT if pretrained else None
        model = mobilenet_v3_small(weights=weights)
        self.features = model.features
        self.avgpool = model.avgpool
        self.projection = model.classifier[0]
        self.activation = model.classifier[1]

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        values = self.features(inputs)
        values = self.avgpool(values)
        values = torch.flatten(values, 1)
        values = self.projection(values)
        return self.activation(values)


class MobileNetViewClassifier(nn.Module):
    """Deployable per-view network with the learned linear BRACS head."""

    def __init__(self, encoder: MobileNetFeatureEncoder, classes: int) -> None:
        super().__init__()
        self.encoder = encoder
        self.head = nn.Linear(1024, classes)

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        return self.head(self.encoder(inputs))


def tensor_transform() -> v2.Compose:
    return v2.Compose(
        [
            v2.ToImage(),
            v2.ToDtype(torch.float32, scale=True),
            v2.Normalize(mean=(0.485, 0.456, 0.406), std=(0.229, 0.224, 0.225)),
        ]
    )


def make_views(image: Image.Image) -> tuple[Image.Image, Image.Image]:
    """Reproduce the lossless global-context and center-detail training views."""
    rgb = image.convert("RGB")
    global_view = ImageOps.pad(
        rgb,
        (IMAGE_SIZE, IMAGE_SIZE),
        method=Image.Resampling.LANCZOS,
        color=(255, 255, 255),
        centering=(0.5, 0.5),
    )
    center_view = ImageOps.fit(
        rgb,
        (IMAGE_SIZE, IMAGE_SIZE),
        method=Image.Resampling.LANCZOS,
        centering=(0.5, 0.5),
    )
    return global_view, center_view


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def softmax(logits: np.ndarray, temperature: float = 1.0) -> np.ndarray:
    scaled = logits / max(float(temperature), 1e-6)
    scaled = scaled - scaled.max(axis=1, keepdims=True)
    values = np.exp(scaled)
    return values / values.sum(axis=1, keepdims=True)


def group_view_logits(
    logits: np.ndarray,
    roi_ids: np.ndarray,
    labels: np.ndarray,
    patients: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    groups: dict[str, list[int]] = defaultdict(list)
    for index, roi_id in enumerate(roi_ids.astype(str)):
        groups[roi_id].append(index)
    ordered = sorted(groups)
    grouped_logits: list[np.ndarray] = []
    grouped_labels: list[int] = []
    grouped_patients: list[str] = []
    for roi_id in ordered:
        indices = groups[roi_id]
        unique_labels = {int(labels[index]) for index in indices}
        unique_patients = {str(patients[index]) for index in indices}
        if len(unique_labels) != 1 or len(unique_patients) != 1:
            raise ValueError(f"inconsistent grouped metadata for {roi_id}")
        grouped_logits.append(logits[indices].mean(axis=0))
        grouped_labels.append(unique_labels.pop())
        grouped_patients.append(unique_patients.pop())
    return (
        np.asarray(grouped_logits),
        np.asarray(grouped_labels, dtype=np.int64),
        np.asarray(grouped_patients),
        np.asarray(ordered),
    )


def metrics_from_probabilities(
    y_true: np.ndarray, probabilities: np.ndarray
) -> dict[str, Any]:
    predictions = probabilities.argmax(axis=1)
    precision, recall, f1, support = precision_recall_fscore_support(
        y_true,
        predictions,
        labels=np.arange(len(LABELS)),
        zero_division=0,
    )
    coarse_true = np.asarray(
        [COARSE_LABELS.index(COARSE_GROUP[LABELS[index]]) for index in y_true]
    )
    coarse_probabilities = np.zeros(
        (len(probabilities), len(COARSE_LABELS)), dtype=np.float64
    )
    for fine_index, label in enumerate(LABELS):
        coarse_probabilities[:, COARSE_LABELS.index(COARSE_GROUP[label])] += (
            probabilities[:, fine_index]
        )
    coarse_predictions = coarse_probabilities.argmax(axis=1)
    return {
        "accuracy": float(accuracy_score(y_true, predictions)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, predictions)),
        "macro_f1": float(
            f1_score(y_true, predictions, average="macro", zero_division=0)
        ),
        "weighted_f1": float(
            f1_score(y_true, predictions, average="weighted", zero_division=0)
        ),
        "log_loss": float(
            log_loss(y_true, probabilities, labels=np.arange(len(LABELS)))
        ),
        "top2_accuracy": float(
            np.mean(
                [
                    truth in np.argsort(row)[-2:]
                    for truth, row in zip(y_true, probabilities)
                ]
            )
        ),
        "coarse_accuracy": float(accuracy_score(coarse_true, coarse_predictions)),
        "coarse_macro_f1": float(
            f1_score(coarse_true, coarse_predictions, average="macro", zero_division=0)
        ),
        "per_class": {
            label: {
                "precision": float(precision[index]),
                "recall": float(recall[index]),
                "f1": float(f1[index]),
                "support": int(support[index]),
            }
            for index, label in enumerate(LABELS)
        },
        "confusion_matrix": confusion_matrix(
            y_true, predictions, labels=np.arange(len(LABELS))
        ).tolist(),
    }


def patient_bootstrap_interval(
    y_true: np.ndarray,
    probabilities: np.ndarray,
    patients: np.ndarray,
    *,
    iterations: int = 500,
    seed: int = 20260802,
) -> dict[str, list[float]]:
    unique = np.unique(patients.astype(str))
    by_patient = {
        patient: np.flatnonzero(patients.astype(str) == patient) for patient in unique
    }
    rng = np.random.default_rng(seed)
    values: dict[str, list[float]] = {
        "accuracy": [],
        "macro_f1": [],
        "coarse_accuracy": [],
    }
    for _ in range(iterations):
        sampled = rng.choice(unique, size=len(unique), replace=True)
        indices = np.concatenate([by_patient[patient] for patient in sampled])
        current = metrics_from_probabilities(y_true[indices], probabilities[indices])
        for name, series in values.items():
            series.append(float(current[name]))
    return {
        name: [float(np.quantile(series, 0.025)), float(np.quantile(series, 0.975))]
        for name, series in values.items()
    }


def choose_review_threshold(
    y_true: np.ndarray,
    probabilities: np.ndarray,
    *,
    target_accuracy: float = 0.65,
    minimum_coverage: float = 0.10,
) -> dict[str, float]:
    confidence = probabilities.max(axis=1)
    predictions = probabilities.argmax(axis=1)
    candidates = sorted({float(value) for value in confidence})
    feasible: list[tuple[float, float, float]] = []
    for threshold in candidates:
        selected = confidence >= threshold
        coverage = float(selected.mean())
        if coverage < minimum_coverage:
            continue
        accuracy = float((predictions[selected] == y_true[selected]).mean())
        if accuracy >= target_accuracy:
            feasible.append((coverage, threshold, accuracy))
    if feasible:
        coverage, threshold, accuracy = max(feasible)
        return {
            "threshold": threshold,
            "coverage": coverage,
            "selective_accuracy": accuracy,
        }
    threshold = float(np.quantile(confidence, 0.90))
    selected = confidence >= threshold
    return {
        "threshold": threshold,
        "coverage": float(selected.mean()),
        "selective_accuracy": float((predictions[selected] == y_true[selected]).mean()),
    }


def class_distribution(labels: Iterable[int]) -> dict[str, int]:
    values = list(labels)
    return {label: int(values.count(index)) for index, label in enumerate(LABELS)}
