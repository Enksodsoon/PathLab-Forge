"""Weakly supervised multiple-instance learning for unannotated WSIs."""

from __future__ import annotations

import json
import math
import os
import platform
import random
import sys
import time
from collections import Counter
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import scipy.optimize
import torch
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from torch import nn

from .core import (
    COARSE_GROUP,
    LABEL_TO_INDEX,
    LABELS,
    choose_review_threshold,
    metrics_from_probabilities,
    patient_bootstrap_interval,
    sha256,
    softmax,
    write_json_atomic,
)


@dataclass(frozen=True)
class MilTrainingConfig:
    bags_root: Path
    output_root: Path
    encoder: str = "kaiko-vits16"
    hidden_dim: int = 128
    learning_rate: float = 1e-3
    weight_decay: float = 1e-4
    max_epochs: int = 200
    patience: int = 25
    seed: int = 20260802
    bootstrap_iterations: int = 500
    validation_only: bool = False


@dataclass(frozen=True)
class MilTestEvaluationConfig:
    bags_root: Path
    model_root: Path
    bootstrap_iterations: int = 500
    seed: int = 20260802
    expected_test_slides: int | None = None
    expected_test_patients: int | None = None
    require_selection_record: bool = False


class GatedAttentionMil(nn.Module):
    """Ilse-style gated attention pooling with interpretable tile weights."""

    def __init__(self, input_dim: int, hidden_dim: int, classes: int) -> None:
        super().__init__()
        self.project = nn.Sequential(
            nn.Linear(input_dim, hidden_dim), nn.ReLU(), nn.Dropout(0.25)
        )
        self.attention_v = nn.Linear(hidden_dim, hidden_dim)
        self.attention_u = nn.Linear(hidden_dim, hidden_dim)
        self.attention_w = nn.Linear(hidden_dim, 1)
        self.classifier = nn.Linear(hidden_dim, classes)

    def forward(self, instances: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        encoded = self.project(instances)
        scores = self.attention_w(
            torch.tanh(self.attention_v(encoded))
            * torch.sigmoid(self.attention_u(encoded))
        ).squeeze(-1)
        attention = torch.softmax(scores, dim=0)
        pooled = torch.sum(encoded * attention.unsqueeze(-1), dim=0)
        return self.classifier(pooled), attention

    @torch.jit.export
    def class_evidence(
        self, instances: torch.Tensor
    ) -> tuple[torch.Tensor, torch.Tensor]:
        """Return additive tile contributions for every output class."""

        encoded = self.project(instances)
        scores = self.attention_w(
            torch.tanh(self.attention_v(encoded))
            * torch.sigmoid(self.attention_u(encoded))
        ).squeeze(-1)
        attention = torch.softmax(scores, dim=0)
        contributions = attention.unsqueeze(-1) * torch.matmul(
            encoded, self.classifier.weight.t()
        )
        return contributions, attention


def train_bracs_mil(config: MilTrainingConfig) -> dict[str, Any]:
    """Select on validation and optionally evaluate the locked test split once."""

    started = time.perf_counter()
    torch.manual_seed(config.seed)
    np.random.seed(config.seed)
    random.seed(config.seed)
    torch.use_deterministic_algorithms(True)
    torch.set_num_threads(max(1, min(6, torch.get_num_threads())))
    bags, manifest_hash = _load_bags(config.bags_root, config.encoder)
    by_split = {
        split: [bag for bag in bags if bag["split"] == split]
        for split in ("train", "validation", "test")
    }
    required = ("train", "validation") if config.validation_only else tuple(by_split)
    if any(not by_split[split] for split in required):
        raise ValueError(f"{', '.join(required)} bags are required")
    target_mpps = {float(bag.get("target_mpp", 0.5)) for bag in bags}
    if len(target_mpps) != 1:
        raise ValueError(f"mixed target MPP values in feature bags: {target_mpps}")
    sampling = {
        "target_mpp": target_mpps.pop(),
        "maximum_tiles": max(len(bag["features"]) for bag in bags),
        "method": "deterministic-thumbnail-tissue-grid-v1",
    }
    encoder_sources = {str(bag.get("encoder_source", "not-recorded")) for bag in bags}
    encoder_licenses = {str(bag.get("encoder_license", "not-recorded")) for bag in bags}
    encoder_releases = {str(bag.get("encoder_release", "not-recorded")) for bag in bags}
    encoder_weight_hashes = {
        str(bag.get("encoder_weights_sha256", "not-recorded")) for bag in bags
    }
    encoder_hubconf_hashes = {
        str(bag.get("encoder_hubconf_sha256", "not-recorded")) for bag in bags
    }
    if any(
        len(values) != 1
        for values in (
            encoder_sources,
            encoder_licenses,
            encoder_releases,
            encoder_weight_hashes,
            encoder_hubconf_hashes,
        )
    ):
        raise ValueError("mixed encoder provenance in feature bags")
    encoder_provenance = {
        "identifier": config.encoder,
        "source": encoder_sources.pop(),
        "license": encoder_licenses.pop(),
        "release": encoder_releases.pop(),
        "weights_sha256": encoder_weight_hashes.pop(),
        "hubconf_sha256": encoder_hubconf_hashes.pop(),
    }
    input_dim = int(by_split["train"][0]["features"].shape[1])
    class_counts = np.bincount(
        [LABEL_TO_INDEX[bag["label"]] for bag in by_split["train"]],
        minlength=len(LABELS),
    )
    training_class_probabilities = class_counts.astype(np.float64) / class_counts.sum()
    class_weights = torch.tensor(
        len(by_split["train"]) / (len(LABELS) * np.maximum(class_counts, 1)),
        dtype=torch.float32,
    )
    train_sample_weights = patient_sample_weights(by_split["train"])

    model = GatedAttentionMil(input_dim, config.hidden_dim, len(LABELS))
    optimizer = torch.optim.AdamW(
        model.parameters(), lr=config.learning_rate, weight_decay=config.weight_decay
    )
    loss_fn = nn.CrossEntropyLoss(weight=class_weights)
    best_state: dict[str, torch.Tensor] | None = None
    best_epoch = 0
    best_score = -math.inf
    stale = 0
    history: list[dict[str, float | int]] = []
    generator = random.Random(config.seed)
    for epoch in range(1, config.max_epochs + 1):
        model.train()
        order = list(range(len(by_split["train"])))
        generator.shuffle(order)
        losses: list[float] = []
        for index in order:
            bag = by_split["train"][index]
            features = torch.from_numpy(bag["features"].astype(np.float32))
            if len(features) > 8:
                keep = torch.rand(len(features)) >= 0.10
                if int(keep.sum()) >= 8:
                    features = features[keep]
            truth = torch.tensor([LABEL_TO_INDEX[bag["label"]]])
            optimizer.zero_grad(set_to_none=True)
            logits, _ = model(features)
            loss = loss_fn(logits.unsqueeze(0), truth) * float(
                train_sample_weights[index]
            )
            loss.backward()
            optimizer.step()
            losses.append(float(loss.detach()))
        val_logits, val_labels, _, _ = _predict(model, by_split["validation"])
        val_metrics = metrics_from_probabilities(val_labels, softmax(val_logits))
        score = float(val_metrics["macro_f1"])
        history.append(
            {
                "epoch": epoch,
                "train_loss": float(np.mean(losses)),
                "validation_macro_f1": score,
                "validation_accuracy": float(val_metrics["accuracy"]),
            }
        )
        if score > best_score + 1e-6:
            best_score = score
            best_epoch = epoch
            best_state = {
                name: tensor.detach().cpu().clone()
                for name, tensor in model.state_dict().items()
            }
            stale = 0
        else:
            stale += 1
            if stale >= config.patience:
                break
    if best_state is None:
        raise RuntimeError("MIL training produced no checkpoint")
    model.load_state_dict(best_state)
    model.eval()

    val_logits, val_labels, val_patients, _ = _predict(model, by_split["validation"])
    temperature = _fit_temperature(val_logits, val_labels)
    review = choose_review_threshold(val_labels, softmax(val_logits, temperature))
    val_probabilities = softmax(val_logits, temperature)
    validation_metrics = metrics_from_probabilities(val_labels, val_probabilities)
    validation_metrics["patient_bootstrap_95_ci"] = patient_bootstrap_interval(
        val_labels,
        val_probabilities,
        val_patients,
        iterations=config.bootstrap_iterations,
        seed=config.seed,
    )
    validation_metrics["selective"] = _selective_metrics(
        val_labels, val_probabilities, float(review["threshold"])
    )
    test_metrics: dict[str, Any] | None = None
    test_predictions: tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray] | None = (
        None
    )
    if not config.validation_only:
        test_logits, test_labels, test_patients, test_slides = _predict(
            model, by_split["test"]
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
        test_predictions = (
            test_slides,
            test_patients,
            test_labels,
            test_probabilities,
        )
    baselines = _mean_pool_baselines(
        by_split, config.seed, evaluate_test=not config.validation_only
    )
    baseline_probabilities = baselines.pop("_probabilities")
    majority_probabilities = {
        split: np.tile(training_class_probabilities, (len(split_bags), 1))
        for split, split_bags in by_split.items()
        if split_bags and (split != "test" or not config.validation_only)
    }
    baselines["majority_class"] = {
        "predicted_class": LABELS[int(training_class_probabilities.argmax())],
        "training_class_probabilities": {
            label: float(training_class_probabilities[index])
            for index, label in enumerate(LABELS)
        },
        "validation": metrics_from_probabilities(
            val_labels, majority_probabilities["validation"]
        ),
    }
    if not config.validation_only:
        baselines["majority_class"]["test"] = metrics_from_probabilities(
            test_labels, majority_probabilities["test"]
        )
    paired_comparison = {
        "validation_mil_minus_mean_pool": (
            _patient_bootstrap_difference(
                val_labels,
                val_probabilities,
                baseline_probabilities["validation"],
                val_patients,
                iterations=config.bootstrap_iterations,
                seed=config.seed,
            )
        ),
        "validation_mil_minus_majority": _patient_bootstrap_difference(
            val_labels,
            val_probabilities,
            majority_probabilities["validation"],
            val_patients,
            iterations=config.bootstrap_iterations,
            seed=config.seed,
        ),
    }
    if test_metrics is not None:
        paired_comparison["test_mil_minus_mean_pool"] = _patient_bootstrap_difference(
            test_labels,
            test_probabilities,
            baseline_probabilities["test"],
            test_patients,
            iterations=config.bootstrap_iterations,
            seed=config.seed,
        )
        paired_comparison["test_mil_minus_majority"] = _patient_bootstrap_difference(
            test_labels,
            test_probabilities,
            majority_probabilities["test"],
            test_patients,
            iterations=config.bootstrap_iterations,
            seed=config.seed,
        )

    output_root = config.output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    scripted = torch.jit.script(model.cpu())
    artifact = output_root / "pathlab_bracs_kaiko_mil_v1.ts"
    torch.jit.save(scripted, artifact)
    artifact_verification = _verify_scripted_mil(
        artifact,
        model,
        by_split["validation"][0]["features"],
        temperature,
    )
    checkpoint_path = output_root / "research_checkpoint.pt"
    baseline_path = output_root / "mean_pool_baselines.joblib"
    torch.save(best_state, checkpoint_path)
    joblib.dump(baselines.pop("_objects"), baseline_path)
    if test_predictions is not None:
        _write_predictions(
            output_root / "test_predictions.csv",
            *test_predictions,
            float(review["threshold"]),
        )
    result: dict[str, Any] = {
        "schema_version": 1,
        "model_name": "PathLab BRACS Kaiko gated-attention MIL v1",
        "task": "seven-class weakly supervised breast pathology WSI classification",
        "labels": list(LABELS),
        "coarse_groups": COARSE_GROUP,
        "encoder": config.encoder,
        "encoder_provenance": encoder_provenance,
        "input_dimension": input_dim,
        "hidden_dimension": config.hidden_dim,
        "sampling": sampling,
        "training_parameters": {
            "learning_rate": config.learning_rate,
            "weight_decay": config.weight_decay,
            "maximum_epochs": config.max_epochs,
            "patience": config.patience,
            "seed": config.seed,
            "bootstrap_iterations": config.bootstrap_iterations,
            "optimizer": "AdamW",
            "loss": "class-weighted cross entropy",
            "tile_dropout_probability": 0.10,
            "patient_balanced_training": True,
            "pytorch_deterministic_algorithms": (
                torch.are_deterministic_algorithms_enabled()
            ),
        },
        "best_epoch": best_epoch,
        "validation_best_macro_f1": best_score,
        "temperature": temperature,
        "review_threshold": review,
        "validation": validation_metrics,
        "test": test_metrics,
        "baselines": baselines,
        "paired_comparison": paired_comparison,
        "splits": {
            split: {
                "slides": len(by_split[split]),
                "patients": len({bag["patient_id"] for bag in by_split[split]}),
            }
            for split in by_split
        },
        "bags_manifest_sha256": manifest_hash,
        "training_history": history,
        "runtime_seconds": time.perf_counter() - started,
        "runtime_environment": _runtime_environment(),
        "artifacts": {
            "torchscript": artifact.name,
            "torchscript_sha256": sha256(artifact),
            "research_checkpoint": checkpoint_path.name,
            "research_checkpoint_sha256": sha256(checkpoint_path),
            "mean_pool_baselines": baseline_path.name,
            "mean_pool_baselines_sha256": sha256(baseline_path),
            "test_predictions": "test_predictions.csv"
            if test_predictions is not None
            else None,
        },
        "artifact_verification": artifact_verification,
        "intended_use": "research and education; not for clinical diagnosis",
        "test_split_touched_once_after_validation_selection": not config.validation_only,
        "selection_stage": "validation_only"
        if config.validation_only
        else "final_test_evaluation",
    }
    write_json_atomic(output_root / "evaluation.json", result)
    write_json_atomic(
        output_root / "model_config.json",
        {
            "schema_version": 1,
            "model_name": result["model_name"],
            "labels": list(LABELS),
            "coarse_groups": COARSE_GROUP,
            "encoder": config.encoder,
            "encoder_provenance": encoder_provenance,
            "input_dimension": input_dim,
            "hidden_dimension": config.hidden_dim,
            "sampling": sampling,
            "temperature": temperature,
            "review_threshold": float(review["threshold"]),
            "review_policy": review,
            "training_class_probabilities": {
                label: float(training_class_probabilities[index])
                for index, label in enumerate(LABELS)
            },
            "torchscript": artifact.name,
            "torchscript_sha256": sha256(artifact),
            "mean_pool_baselines": baseline_path.name,
            "mean_pool_baselines_sha256": sha256(baseline_path),
            "intended_use": result["intended_use"],
            "selection_stage": result["selection_stage"],
        },
    )
    return result


def evaluate_bracs_mil_test(config: MilTestEvaluationConfig) -> dict[str, Any]:
    """Evaluate a frozen validation-selected MIL artifact on test exactly once."""

    started = time.perf_counter()
    model_root = config.model_root.resolve()
    output_path = model_root / "final_test_evaluation.json"
    if output_path.exists():
        raise FileExistsError(
            "final test evaluation already exists; refusing to touch test again"
        )
    model_config = json.loads(
        (model_root / "model_config.json").read_text(encoding="utf-8")
    )
    selection_path = model_root.parent / "selected_model.json"
    selection_record_sha256: str | None = None
    if config.require_selection_record and not selection_path.is_file():
        raise ValueError("validation selection record is required")
    if selection_path.is_file():
        selection_record_sha256 = sha256(selection_path)
        selection = json.loads(selection_path.read_text(encoding="utf-8"))
        if int(selection.get("schema_version", 1)) < 2:
            raise ValueError("locked test requires a v2 validation selection record")
        if not bool(selection.get("advance_to_locked_test")):
            raise ValueError("validation selection did not pass advancement gates")
        if Path(selection["candidate_path"]).resolve() != model_root:
            raise ValueError("selection record points to a different model")
        if selection["artifact_sha256"] != model_config["torchscript_sha256"]:
            raise ValueError("selection record TorchScript checksum mismatch")
        if selection["model_config_sha256"] != sha256(model_root / "model_config.json"):
            raise ValueError("selection record model-config checksum mismatch")
        if selection["evaluation_sha256"] != sha256(model_root / "evaluation.json"):
            raise ValueError("selection record evaluation checksum mismatch")
        if (
            selection["mean_pool_baselines_sha256"]
            != model_config["mean_pool_baselines_sha256"]
        ):
            raise ValueError("selection record baseline checksum mismatch")
        grid_path = model_root.parent / "validation_grid.json"
        if selection.get("validation_grid_sha256") != sha256(grid_path):
            raise ValueError("selection record validation-grid checksum mismatch")
        grid = json.loads(grid_path.read_text(encoding="utf-8"))
        if (
            grid.get("selected") != selection.get("candidate")
            or grid.get("advancement_gates") != selection.get("advancement_gates")
            or bool(grid.get("advance_to_locked_test"))
            != bool(selection.get("advance_to_locked_test"))
        ):
            raise ValueError("selection record disagrees with validation grid")
        selected_rows = [
            row
            for row in grid.get("candidates", [])
            if row.get("name") == selection.get("candidate")
        ]
        hash_fields = (
            "artifact_sha256",
            "model_config_sha256",
            "evaluation_sha256",
            "mean_pool_baselines_sha256",
        )
        if len(selected_rows) != 1 or any(
            selected_rows[0].get(field) != selection.get(field) for field in hash_fields
        ):
            raise ValueError("selected artifact hashes disagree with validation grid")
    if model_config.get("selection_stage") != "validation_only":
        raise ValueError("model was not frozen by validation-only selection")
    artifact = model_root / model_config["torchscript"]
    if sha256(artifact) != model_config["torchscript_sha256"]:
        raise ValueError("MIL artifact checksum mismatch")
    baseline_path = model_root / model_config["mean_pool_baselines"]
    if sha256(baseline_path) != model_config["mean_pool_baselines_sha256"]:
        raise ValueError("mean-pool baseline checksum mismatch")
    attempt_lock: Path | None = None
    if config.require_selection_record:
        attempt_lock = _acquire_locked_test_attempt(
            model_root, selection_record_sha256=selection_record_sha256
        )
    bags, manifest_hash = _load_bags(config.bags_root, model_config["encoder"])
    test_bags = [bag for bag in bags if bag["split"] == "test"]
    if not test_bags:
        raise ValueError("test bags are required for final evaluation")
    test_patients = {str(bag["patient_id"]) for bag in test_bags}
    test_labels = {str(bag["label"]) for bag in test_bags}
    if config.expected_test_slides is not None and len(test_bags) != int(
        config.expected_test_slides
    ):
        raise ValueError(
            "locked test slide count mismatch: "
            f"expected {config.expected_test_slides}, found {len(test_bags)}"
        )
    if config.expected_test_patients is not None and len(test_patients) != int(
        config.expected_test_patients
    ):
        raise ValueError(
            "locked test patient count mismatch: "
            f"expected {config.expected_test_patients}, found {len(test_patients)}"
        )
    if test_labels != set(LABELS):
        raise ValueError(
            f"locked test bags do not cover every BRACS class: {sorted(test_labels)}"
        )
    model = torch.jit.load(str(artifact), map_location="cpu").eval()
    logits, labels, patients, slides = _predict(model, test_bags)
    probabilities = softmax(logits, float(model_config["temperature"]))
    metrics = metrics_from_probabilities(labels, probabilities)
    metrics["patient_bootstrap_95_ci"] = patient_bootstrap_interval(
        labels,
        probabilities,
        patients,
        iterations=config.bootstrap_iterations,
        seed=config.seed,
    )
    metrics["selective"] = _selective_metrics(
        labels, probabilities, float(model_config["review_threshold"])
    )
    baseline_objects = joblib.load(baseline_path)
    test_means = np.vstack(
        [bag["features"].astype(np.float32).mean(axis=0) for bag in test_bags]
    )
    baseline_probabilities = baseline_objects["classifier"].predict_proba(
        baseline_objects["scaler"].transform(test_means)
    )
    majority_distribution = np.asarray(
        [model_config["training_class_probabilities"][label] for label in LABELS],
        dtype=np.float64,
    )
    majority_probabilities = np.tile(majority_distribution, (len(test_bags), 1))
    predictions_path = model_root / "final_test_predictions.csv"
    _write_predictions(
        predictions_path,
        slides,
        patients,
        labels,
        probabilities,
        float(model_config["review_threshold"]),
    )
    result = {
        "schema_version": 1,
        "evaluation_stage": "locked_test_once",
        "model_artifact_sha256": model_config["torchscript_sha256"],
        "selection_record_sha256": selection_record_sha256,
        "attempt_lock_sha256": sha256(attempt_lock) if attempt_lock else None,
        "bags_manifest_sha256": manifest_hash,
        "slides": len(test_bags),
        "patients": len(test_patients),
        "metrics": metrics,
        "mean_pool_baseline": metrics_from_probabilities(
            labels, baseline_probabilities
        ),
        "majority_class_baseline": metrics_from_probabilities(
            labels, majority_probabilities
        ),
        "mil_minus_mean_pool": (
            _patient_bootstrap_difference(
                labels,
                probabilities,
                baseline_probabilities,
                patients,
                iterations=config.bootstrap_iterations,
                seed=config.seed,
            )
        ),
        "mil_minus_majority": _patient_bootstrap_difference(
            labels,
            probabilities,
            majority_probabilities,
            patients,
            iterations=config.bootstrap_iterations,
            seed=config.seed,
        ),
        "runtime_seconds": time.perf_counter() - started,
        "runtime_environment": _runtime_environment(),
        "predictions": predictions_path.name,
        "intended_use": "research and education; not for clinical diagnosis",
    }
    write_json_atomic(output_path, result)
    return result


def _acquire_locked_test_attempt(
    model_root: Path, *, selection_record_sha256: str | None = None
) -> Path:
    """Atomically and durably record that locked-test exposure has begun."""

    lock_path = model_root / "final_test_evaluation.lock"
    try:
        descriptor = os.open(
            lock_path,
            os.O_CREAT | os.O_EXCL | os.O_WRONLY,
            0o600,
        )
    except FileExistsError as error:
        raise RuntimeError(
            "locked test evaluation was already started; refusing another attempt"
        ) from error
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(
            {
                "schema_version": 1,
                "status": "locked_test_evaluation_started",
                "started_at_utc": datetime.now(UTC).isoformat(),
                "process_id": os.getpid(),
                "selection_record_sha256": selection_record_sha256,
            },
            handle,
            sort_keys=True,
        )
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    return lock_path


def _runtime_environment() -> dict[str, Any]:
    import sklearn

    return {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor(),
        "python": sys.version.split()[0],
        "numpy": np.__version__,
        "pytorch": torch.__version__,
        "scikit_learn": sklearn.__version__,
        "torch_threads": torch.get_num_threads(),
        "pytorch_deterministic_algorithms": (
            torch.are_deterministic_algorithms_enabled()
        ),
        "process_memory": _process_memory_usage(),
    }


def _process_memory_usage() -> dict[str, int | None]:
    """Return native current/peak process memory without optional dependencies."""

    if sys.platform == "win32":
        import ctypes
        from ctypes import wintypes

        class ProcessMemoryCounters(ctypes.Structure):
            _fields_ = [
                ("cb", wintypes.DWORD),
                ("PageFaultCount", wintypes.DWORD),
                ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t),
                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t),
                ("PrivateUsage", ctypes.c_size_t),
            ]

        counters = ProcessMemoryCounters()
        counters.cb = ctypes.sizeof(counters)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        kernel32.GetCurrentProcess.argtypes = []
        kernel32.GetCurrentProcess.restype = wintypes.HANDLE
        psapi.GetProcessMemoryInfo.argtypes = [
            wintypes.HANDLE,
            ctypes.POINTER(ProcessMemoryCounters),
            wintypes.DWORD,
        ]
        psapi.GetProcessMemoryInfo.restype = wintypes.BOOL
        process = kernel32.GetCurrentProcess()
        succeeded = psapi.GetProcessMemoryInfo(
            process, ctypes.byref(counters), counters.cb
        )
        if succeeded:
            return {
                "working_set_bytes": int(counters.WorkingSetSize),
                "peak_working_set_bytes": int(counters.PeakWorkingSetSize),
                "private_usage_bytes": int(counters.PrivateUsage),
            }
    else:
        try:
            import resource

            peak = int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
            if sys.platform != "darwin":
                peak *= 1024
            return {
                "working_set_bytes": None,
                "peak_working_set_bytes": peak,
                "private_usage_bytes": None,
            }
        except (ImportError, OSError):
            pass
    return {
        "working_set_bytes": None,
        "peak_working_set_bytes": None,
        "private_usage_bytes": None,
    }


def patient_sample_weights(bags: list[dict[str, Any]]) -> np.ndarray:
    """Give every patient equal total weight while preserving mean weight one."""

    if not bags:
        raise ValueError("patient weighting requires at least one bag")
    patient_counts = Counter(str(bag["patient_id"]) for bag in bags)
    weight_scale = len(bags) / len(patient_counts)
    return np.asarray(
        [weight_scale / patient_counts[str(bag["patient_id"])] for bag in bags],
        dtype=np.float64,
    )


def _patient_bootstrap_difference(
    labels: np.ndarray,
    candidate_probabilities: np.ndarray,
    baseline_probabilities: np.ndarray,
    patients: np.ndarray,
    *,
    iterations: int,
    seed: int,
) -> dict[str, Any]:
    unique = np.unique(patients.astype(str))
    by_patient = {
        patient: np.flatnonzero(patients.astype(str) == patient) for patient in unique
    }
    rng = np.random.default_rng(seed)
    differences: dict[str, list[float]] = {
        "accuracy": [],
        "macro_f1": [],
        "coarse_accuracy": [],
    }
    for _ in range(iterations):
        sampled = rng.choice(unique, size=len(unique), replace=True)
        indices = np.concatenate([by_patient[patient] for patient in sampled])
        candidate = metrics_from_probabilities(
            labels[indices], candidate_probabilities[indices]
        )
        baseline = metrics_from_probabilities(
            labels[indices], baseline_probabilities[indices]
        )
        for name, values in differences.items():
            values.append(float(candidate[name]) - float(baseline[name]))
    candidate_point = metrics_from_probabilities(labels, candidate_probabilities)
    baseline_point = metrics_from_probabilities(labels, baseline_probabilities)
    return {
        "bootstrap_unit": "patient",
        "iterations": iterations,
        "point_estimate": {
            name: float(candidate_point[name]) - float(baseline_point[name])
            for name in differences
        },
        "95_ci": {
            name: [
                float(np.quantile(values, 0.025)),
                float(np.quantile(values, 0.975)),
            ]
            for name, values in differences.items()
        },
    }


def _verify_scripted_mil(
    artifact: Path,
    eager_model: GatedAttentionMil,
    features: np.ndarray,
    temperature: float,
) -> dict[str, float | bool]:
    loaded = torch.jit.load(str(artifact), map_location="cpu").eval()
    tensor = torch.from_numpy(features.astype(np.float32, copy=False))
    eager_model.eval()
    with torch.inference_mode():
        eager_logits, eager_attention = eager_model(tensor)
        scripted_logits, scripted_attention = loaded(tensor)
        contributions, evidence_attention = loaded.class_evidence(tensor)
        reconstructed = contributions.sum(dim=0) + eager_model.classifier.bias
        probabilities = torch.softmax(scripted_logits / float(temperature), dim=0)
    result = {
        "logit_max_absolute_error": float(
            torch.max(torch.abs(eager_logits - scripted_logits))
        ),
        "attention_max_absolute_error": float(
            torch.max(torch.abs(eager_attention - scripted_attention))
        ),
        "evidence_attention_max_absolute_error": float(
            torch.max(torch.abs(scripted_attention - evidence_attention))
        ),
        "evidence_reconstruction_max_absolute_error": float(
            torch.max(torch.abs(scripted_logits - reconstructed))
        ),
        "attention_sum": float(scripted_attention.sum()),
        "probability_sum": float(probabilities.sum()),
        "finite": bool(
            torch.isfinite(scripted_logits).all()
            and torch.isfinite(scripted_attention).all()
            and torch.isfinite(contributions).all()
            and torch.isfinite(probabilities).all()
        ),
    }
    if (
        not result["finite"]
        or result["logit_max_absolute_error"] > 1e-5
        or result["attention_max_absolute_error"] > 1e-5
        or result["evidence_attention_max_absolute_error"] > 1e-5
        or result["evidence_reconstruction_max_absolute_error"] > 1e-5
        or abs(result["attention_sum"] - 1.0) > 1e-5
        or abs(result["probability_sum"] - 1.0) > 1e-5
    ):
        raise RuntimeError(f"TorchScript MIL artifact verification failed: {result}")
    return result


def _load_bags(root: Path, encoder: str) -> tuple[list[dict[str, Any]], str]:
    root = root.resolve()
    manifest = root / "bags.jsonl"
    records = [
        json.loads(line)
        for line in manifest.read_text(encoding="utf-8").splitlines()
        if line
    ]
    bags: list[dict[str, Any]] = []
    patients: dict[str, set[str]] = {}
    slide_ids: set[str] = set()
    feature_dimension: int | None = None
    for record in records:
        if record["encoder"] != encoder:
            continue
        if record["label"] not in LABEL_TO_INDEX:
            raise ValueError(f"unknown bag label: {record['label']}")
        if record["split"] not in {"train", "validation", "test"}:
            raise ValueError(f"unknown bag split: {record['split']}")
        slide_id = str(record["slide_id"])
        if slide_id in slide_ids:
            raise ValueError(f"duplicate slide in bags manifest: {slide_id}")
        slide_ids.add(slide_id)
        path = root / record["bag_path"]
        if "bag_sha256" in record and sha256(path) != record["bag_sha256"]:
            raise ValueError(f"bag checksum mismatch: {path}")
        with np.load(path, allow_pickle=False) as loaded:
            features = loaded["features"]
        if features.ndim != 2 or not np.isfinite(features).all():
            raise ValueError(f"invalid bag features: {path}")
        if feature_dimension is None:
            feature_dimension = int(features.shape[1])
        elif features.shape[1] != feature_dimension:
            raise ValueError(f"inconsistent feature dimension: {path}")
        patients.setdefault(str(record["patient_id"]), set()).add(record["split"])
        bags.append({**record, "features": features})
    leaked = [patient for patient, splits in patients.items() if len(splits) > 1]
    if leaked:
        raise ValueError(f"patient leakage across bag splits: {leaked[:5]}")
    for split in ("train", "validation"):
        labels = {bag["label"] for bag in bags if bag["split"] == split}
        if labels != set(LABELS):
            raise ValueError(f"{split} bags do not cover all labels: {sorted(labels)}")
    return bags, sha256(manifest)


def _predict(
    model: GatedAttentionMil, bags: list[dict[str, Any]]
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    logits: list[np.ndarray] = []
    labels: list[int] = []
    patients: list[str] = []
    slides: list[str] = []
    model.eval()
    with torch.inference_mode():
        for bag in bags:
            output, _ = model(torch.from_numpy(bag["features"].astype(np.float32)))
            logits.append(output.cpu().numpy())
            labels.append(LABEL_TO_INDEX[bag["label"]])
            patients.append(str(bag["patient_id"]))
            slides.append(str(bag["slide_id"]))
    return (
        np.vstack(logits),
        np.asarray(labels),
        np.asarray(patients),
        np.asarray(slides),
    )


def _fit_temperature(logits: np.ndarray, labels: np.ndarray) -> float:
    def objective(log_temperature: float) -> float:
        probabilities = softmax(logits, math.exp(log_temperature))
        return float(
            -np.log(probabilities[np.arange(len(labels)), labels] + 1e-12).mean()
        )

    result = scipy.optimize.minimize_scalar(
        objective, bounds=(-3.0, 3.0), method="bounded"
    )
    return float(math.exp(result.x))


def _selective_metrics(
    labels: np.ndarray, probabilities: np.ndarray, threshold: float
) -> dict[str, float | int]:
    confidence = probabilities.max(axis=1)
    accepted = confidence >= threshold
    predictions = probabilities.argmax(axis=1)
    return {
        "threshold": threshold,
        "accepted": int(accepted.sum()),
        "reviewed": int((~accepted).sum()),
        "coverage": float(accepted.mean()),
        "accepted_accuracy": float((predictions[accepted] == labels[accepted]).mean())
        if accepted.any()
        else 0.0,
    }


def _mean_pool_baselines(
    by_split: dict[str, list[dict[str, Any]]],
    seed: int,
    *,
    evaluate_test: bool = True,
) -> dict[str, Any]:
    arrays: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    for split, bags in by_split.items():
        if not bags:
            continue
        arrays[split] = (
            np.vstack([bag["features"].mean(axis=0) for bag in bags]),
            np.asarray([LABEL_TO_INDEX[bag["label"]] for bag in bags]),
        )
    scaler = StandardScaler().fit(arrays["train"][0])
    train_x = scaler.transform(arrays["train"][0])
    val_x = scaler.transform(arrays["validation"][0])
    selection: list[dict[str, float]] = []
    best: LogisticRegression | None = None
    best_score = -math.inf
    sample_weights = patient_sample_weights(by_split["train"])
    for c_value in (0.001, 0.01, 0.1, 1.0):
        candidate = LogisticRegression(
            C=c_value, class_weight="balanced", max_iter=2000, random_state=seed
        ).fit(train_x, arrays["train"][1], sample_weight=sample_weights)
        score = float(
            metrics_from_probabilities(
                arrays["validation"][1], candidate.predict_proba(val_x)
            )["macro_f1"]
        )
        selection.append({"C": c_value, "validation_macro_f1": score})
        if score > best_score:
            best, best_score = candidate, score
    assert best is not None
    validation_probabilities = best.predict_proba(val_x)
    metrics: dict[str, Any] = {
        "selected_C": float(best.C),
        "validation_selection": selection,
        "validation": metrics_from_probabilities(
            arrays["validation"][1], validation_probabilities
        ),
    }
    probabilities = {"validation": validation_probabilities}
    if evaluate_test:
        test_x = scaler.transform(arrays["test"][0])
        test_probabilities = best.predict_proba(test_x)
        metrics["test"] = metrics_from_probabilities(
            arrays["test"][1], test_probabilities
        )
        probabilities["test"] = test_probabilities
    return {
        "mean_pool_logistic": {
            **metrics,
        },
        "_objects": {"scaler": scaler, "classifier": best},
        "_probabilities": probabilities,
    }


def _write_predictions(
    path: Path,
    slides: np.ndarray,
    patients: np.ndarray,
    labels: np.ndarray,
    probabilities: np.ndarray,
    threshold: float,
) -> None:
    import csv

    with path.open("w", encoding="utf-8", newline="") as handle:
        fields = [
            "slide_id",
            "patient_id",
            "truth",
            "prediction",
            "confidence",
            "needs_review",
            *[f"p_{label}" for label in LABELS],
        ]
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for slide, patient, truth, row in zip(slides, patients, labels, probabilities):
            prediction = int(row.argmax())
            writer.writerow(
                {
                    "slide_id": slide,
                    "patient_id": patient,
                    "truth": LABELS[int(truth)],
                    "prediction": LABELS[prediction],
                    "confidence": f"{float(row[prediction]):.8f}",
                    "needs_review": str(float(row[prediction]) < threshold).lower(),
                    **{
                        f"p_{label}": f"{float(row[index]):.8f}"
                        for index, label in enumerate(LABELS)
                    },
                }
            )
