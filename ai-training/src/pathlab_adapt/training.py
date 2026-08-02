"""Bounded, checkpointed TRACE-SIM demonstration training."""

from __future__ import annotations

import json
import math
import random
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from .evaluation import Prediction, evaluate_predictions
from .features import FEATURE_NAMES, EncodedWindow, encode_windows
from .io import sha256_file, write_json_atomic
from .models import TRACEFormerConfig, build_trace_former
from .ontology import LearnerEvent, TRACE_SIM_HEADS


@dataclass(frozen=True, slots=True)
class TrainingConfig:
    seed: int = 20260802
    context: int = 32
    stride: int = 8
    epochs: int = 6
    patience: int = 2
    batch_size: int = 64
    learning_rate: float = 0.001
    max_windows: int = 40_000


def train_trace_sim(
    events: list[LearnerEvent], model_config: TRACEFormerConfig, output_dir: Path,
    config: TrainingConfig = TrainingConfig(),
) -> dict[str, Any]:
    try:
        import numpy as np
        import torch
        from torch.nn import functional
    except ImportError as error:
        raise RuntimeError("Training requires pathlab-ai-data[adapt-train]") from error
    random.seed(config.seed); np.random.seed(config.seed); torch.manual_seed(config.seed)
    torch.set_num_threads(max(1, min(6, torch.get_num_threads())))
    windows = encode_windows(events, vocabulary=model_config.token_vocabulary, context=config.context, stride=config.stride)
    rng = random.Random(config.seed); rng.shuffle(windows)
    if len(windows) > config.max_windows:
        windows = windows[:config.max_windows]
    learner_ids = sorted({window.learner_id for window in windows})
    validation_learners = set(learner_ids[::5])
    train_rows = [row for row in windows if row.learner_id not in validation_learners]
    validation_rows = [row for row in windows if row.learner_id in validation_learners]
    if not train_rows or not validation_rows:
        raise ValueError("learner-disjoint train and validation windows are required")
    model = build_trace_former(model_config)
    optimizer = torch.optim.AdamW(model.parameters(), lr=config.learning_rate, weight_decay=0.01)
    output_dir.mkdir(parents=True, exist_ok=True)
    best_path = output_dir / "best.pt"
    history: list[dict[str, float]] = []
    best_brier = math.inf; stale = 0; started = time.perf_counter()
    for epoch in range(config.epochs):
        model.train(); epoch_rng = random.Random(config.seed + epoch); epoch_rng.shuffle(train_rows)
        total_loss = 0.0; batch_count = 0
        for start in range(0, len(train_rows), config.batch_size):
            batch = train_rows[start:start + config.batch_size]
            tokens, features, targets = _batch(torch, batch, model_config, config.context)
            optimizer.zero_grad(set_to_none=True)
            outputs = model(tokens, features)
            loss = sum(functional.binary_cross_entropy_with_logits(outputs[name], targets[name]) for name in TRACE_SIM_HEADS) / len(TRACE_SIM_HEADS)
            loss.backward(); torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0); optimizer.step()
            total_loss += float(loss.detach()); batch_count += 1
        metrics = _evaluate(torch, model, validation_rows, model_config, config)
        row = {"epoch": float(epoch + 1), "train_loss": total_loss / max(1, batch_count), **metrics}
        history.append(row)
        if metrics["retention_brier"] < best_brier - 0.0005:
            best_brier = metrics["retention_brier"]; stale = 0
            torch.save(model.state_dict(), best_path)
        else:
            stale += 1
            if stale >= config.patience:
                break
    model.load_state_dict(torch.load(best_path, map_location="cpu", weights_only=True))
    final = _evaluate(torch, model, validation_rows, model_config, config)
    result = {
        "schema": "pathlab.trace-sim-training/2", "scope": "synthetic_software_recovery_only",
        "config": asdict(config), "model_config": asdict(model_config),
        "train_windows": len(train_rows), "validation_windows": len(validation_rows),
        "learner_disjoint": True, "history": history, "metrics": final,
        "checkpoint": str(best_path.resolve()), "checkpoint_sha256": sha256_file(best_path),
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "effectiveness_claim_permitted": False,
    }
    write_json_atomic(output_dir / "training-report.json", result)
    return result


def _batch(torch: Any, rows: list[EncodedWindow], model_config: TRACEFormerConfig, context: int) -> tuple[Any, Any, dict[str, Any]]:
    tokens = torch.zeros((len(rows), context), dtype=torch.long)
    features = torch.zeros((len(rows), context, len(FEATURE_NAMES)), dtype=torch.float32)
    for index, row in enumerate(rows):
        width = min(context, len(row.tokens)); tokens[index, -width:] = torch.tensor(row.tokens[-width:], dtype=torch.long)
        features[index, -width:] = torch.tensor(row.features[-width:], dtype=torch.float32)
    targets = {name: torch.tensor([row.targets[name] for row in rows], dtype=torch.float32) for name in TRACE_SIM_HEADS}
    return tokens, features, targets


def _evaluate(torch: Any, model: Any, rows: list[EncodedWindow], model_config: TRACEFormerConfig, config: TrainingConfig) -> dict[str, float]:
    model.eval(); probabilities = {name: [] for name in TRACE_SIM_HEADS}; targets = {name: [] for name in TRACE_SIM_HEADS}
    with torch.no_grad():
        for start in range(0, len(rows), config.batch_size):
            batch = rows[start:start + config.batch_size]
            token_tensor, feature_tensor, target_tensor = _batch(torch, batch, model_config, config.context)
            outputs = model(token_tensor, feature_tensor)
            for name in TRACE_SIM_HEADS:
                probabilities[name].extend(torch.sigmoid(outputs[name]).cpu().tolist()); targets[name].extend(target_tensor[name].cpu().tolist())
    metrics: dict[str, float] = {}
    for name in TRACE_SIM_HEADS:
        evaluated = evaluate_predictions([Prediction(rows[index].event_id, rows[index].learner_id, int(target), float(probability)) for index, (target, probability) in enumerate(zip(targets[name], probabilities[name]))])
        metrics[f"{name}_brier"] = evaluated.brier; metrics[f"{name}_auroc"] = evaluated.auroc; metrics[f"{name}_ece"] = evaluated.ece
    metrics["mean_brier"] = sum(metrics[f"{name}_brier"] for name in TRACE_SIM_HEADS) / len(TRACE_SIM_HEADS)
    return metrics


def load_parquet_events(manifest_path: Path, *, max_events: int | None = None) -> list[LearnerEvent]:
    try:
        import pyarrow.parquet as pq
    except ImportError as error:
        raise RuntimeError("Parquet loading requires pathlab-ai-data[adapt-train]") from error
    from .artifact_store import verify_dataset_manifest
    manifest = verify_dataset_manifest(manifest_path); rows: list[LearnerEvent] = []
    for shard in manifest["shards"]:
        for item in pq.read_table(manifest_path.parent / str(shard["file"])).to_pylist():
            item["metadata"] = json.loads(item["metadata"])
            rows.append(LearnerEvent(**item))
            if max_events is not None and len(rows) >= max_events:
                return rows
    return rows
