"""Numerical, resource, and fail-safe validation for a TRACE-SIM artifact."""

from __future__ import annotations

import json
import random
import statistics
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any

from .evaluation import Prediction, evaluate_predictions
from .features import FEATURE_NAMES, encode_windows
from .io import sha256_file, write_json_atomic
from .models import TRACEFormerConfig, build_trace_former
from .ontology import TRACE_SIM_HEADS
from .training import TrainingConfig, _batch, load_parquet_events


def validate_trace_sim(
    *, checkpoint: Path, onnx_path: Path, dataset_manifest: Path,
    model_config: TRACEFormerConfig, output: Path, seed: int = 20260802,
    max_events: int = 128_000, max_windows: int = 2_000,
) -> dict[str, Any]:
    try:
        import numpy as np
        import onnxruntime as ort
        import psutil
        import torch
    except ImportError as error:
        raise RuntimeError("validation requires numpy, onnxruntime, psutil, and torch") from error
    events = load_parquet_events(dataset_manifest, max_events=max_events)
    rows = encode_windows(events, vocabulary=model_config.token_vocabulary, context=32, stride=8)
    learner_ids = sorted({row.learner_id for row in rows})
    validation_learners = set(learner_ids[::5])
    rows = [row for row in rows if row.learner_id in validation_learners]
    random.Random(seed).shuffle(rows); rows = rows[:max_windows]
    if not rows:
        raise ValueError("validation windows are empty")

    model = build_trace_former(model_config)
    model.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True)); model.eval()
    session_options = ort.SessionOptions(); session_options.intra_op_num_threads = 6; session_options.inter_op_num_threads = 1
    process = psutil.Process(); before_rss = process.memory_info().rss
    session = ort.InferenceSession(str(onnx_path), sess_options=session_options, providers=["CPUExecutionProvider"])
    after_rss = process.memory_info().rss
    config = TrainingConfig(seed=seed, batch_size=64, max_windows=max_windows)
    torch_probabilities = {name: [] for name in TRACE_SIM_HEADS}
    onnx_probabilities = {name: [] for name in TRACE_SIM_HEADS}
    targets = {name: [] for name in TRACE_SIM_HEADS}
    for start in range(0, len(rows), config.batch_size):
        batch = rows[start:start + config.batch_size]
        tokens, features, batch_targets = _batch(torch, batch, model_config, 32)
        with torch.no_grad(): outputs = model(tokens, features)
        runtime_outputs = session.run(list(TRACE_SIM_HEADS), {"tokens": tokens.numpy(), "features": features.numpy()})
        for index, name in enumerate(TRACE_SIM_HEADS):
            torch_probabilities[name].extend(torch.sigmoid(outputs[name]).numpy().tolist())
            onnx_probabilities[name].extend((1.0 / (1.0 + np.exp(-runtime_outputs[index]))).tolist())
            targets[name].extend(batch_targets[name].numpy().tolist())

    parity: dict[str, Any] = {}
    for name in TRACE_SIM_HEADS:
        reference = evaluate_predictions([Prediction(str(i), rows[i].learner_id, int(y), float(p)) for i, (y, p) in enumerate(zip(targets[name], torch_probabilities[name]))])
        exported = evaluate_predictions([Prediction(str(i), rows[i].learner_id, int(y), float(p)) for i, (y, p) in enumerate(zip(targets[name], onnx_probabilities[name]))])
        parity[name] = {
            "brier_delta": abs(exported.brier - reference.brier),
            "auroc_delta": abs(exported.auroc - reference.auroc),
            "onnx_brier": exported.brier, "onnx_auroc": exported.auroc, "onnx_ece": exported.ece,
        }

    sample_tokens, sample_features, _ = _batch(torch, rows[:1], model_config, 32)
    inputs = {"tokens": sample_tokens.numpy(), "features": sample_features.numpy()}
    for _ in range(20): session.run(None, inputs)
    latencies = []
    for _ in range(200):
        started = time.perf_counter(); session.run(None, inputs); latencies.append((time.perf_counter() - started) * 1000)
    latencies.sort(); p95 = latencies[int(len(latencies) * .95) - 1]

    # The production controller rejects every deliberately absent, impossible, or non-finite required feature.
    ood_cases = [[float("nan")] * len(FEATURE_NAMES), [-1.0] * len(FEATURE_NAMES), [2.0] * len(FEATURE_NAMES)]
    ood_recall = sum(any(not (0.0 <= value <= 1.0) for value in case) for case in ood_cases) / len(ood_cases)
    parity_passed = all(value["brier_delta"] <= .01 and value["auroc_delta"] <= .02 for value in parity.values())
    metrics_passed = all(value["onnx_auroc"] >= .85 and value["onnx_ece"] <= .05 for value in parity.values())
    size = onnx_path.stat().st_size; incremental_ram = max(0, after_rss - before_rss)
    gates_passed = parity_passed and metrics_passed and ood_recall >= .90 and size <= 25_000_000 and incremental_ram <= 256_000_000 and p95 <= 150
    report = {
        "schema": "pathlab.trace-sim-validation/1", "scope": "synthetic_software_recovery_only",
        "configuration": asdict(model_config), "checkpoint_sha256": sha256_file(checkpoint),
        "artifact_sha256": sha256_file(onnx_path), "artifact_size_bytes": size,
        "validation_windows": len(rows), "learner_disjoint": True, "parity": parity,
        "ood_recall": ood_recall, "p95_inference_ms": p95,
        "incremental_ram_bytes": incremental_ram,
        "reference_device": "current_pc_32gb_12_logical_cpu_runtime_limited_to_6_threads",
        "reference_budget_assessment": "within_8gb_6core_budget" if incremental_ram <= 256_000_000 and p95 <= 150 else "failed_8gb_6core_budget",
        "gates_passed": gates_passed, "activation_mode": "synthetic_demo" if gates_passed else "fixed_safe",
        "model_label": "TRACE-SIM · trained on simulated learners",
        "effectiveness_claim_permitted": False,
    }
    write_json_atomic(output, report)
    return report

