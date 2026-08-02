"""Measured benchmark assembly from aligned predictions and resource evidence."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass

from .baselines import BASELINE_NAMES, BaselineResult
from .evaluation import (
    Prediction,
    PredictionMetrics,
    bootstrap_relative_brier_improvement,
    evaluate_predictions,
)
from .pareto import CandidateEvidence


def _valid_sha256(value: str) -> bool:
    return len(value) == 64 and all(character in "0123456789abcdef" for character in value)


@dataclass(frozen=True, slots=True)
class ResourceEvidence:
    artifact_size_bytes: int
    artifact_sha256: str
    incremental_ram_bytes: int
    p95_inference_ms: float
    reference_device: str

    def __post_init__(self) -> None:
        if self.artifact_size_bytes < 0 or self.incremental_ram_bytes < 0 or self.p95_inference_ms < 0:
            raise ValueError("resource measurements must be non-negative")
        if not _valid_sha256(self.artifact_sha256):
            raise ValueError("artifact_sha256 must be a lowercase SHA-256 digest")
        if not self.reference_device.strip():
            raise ValueError("reference_device must name the measured device")


@dataclass(frozen=True, slots=True)
class BenchmarkOutcome:
    evidence: CandidateEvidence
    candidate_metrics: PredictionMetrics
    teacher_metrics: PredictionMetrics
    baselines: tuple[BaselineResult, ...]
    input_hashes: dict[str, str]
    bootstrap: dict[str, float | int]

    def to_dict(self) -> dict[str, object]:
        return {
            "schema_version": "pathlab-adapt-benchmark-v1",
            "evidence": asdict(self.evidence),
            "candidate_metrics": asdict(self.candidate_metrics),
            "teacher_metrics": asdict(self.teacher_metrics),
            "baselines": [asdict(item) for item in self.baselines],
            "input_hashes": dict(sorted(self.input_hashes.items())),
            "bootstrap": self.bootstrap,
        }


def benchmark_candidate(
    candidate: list[Prediction],
    teacher: list[Prediction],
    baseline_predictions: dict[str, list[Prediction]],
    *,
    benchmark_kind: str,
    candidate_id: str,
    resource: ResourceEvidence,
    input_hashes: dict[str, str],
    bootstrap_iterations: int = 1_000,
    seed: int = 20260802,
) -> BenchmarkOutcome:
    """Compute every statistical gate from aligned rows, never supplied metrics."""

    if benchmark_kind not in {"real", "synthetic"}:
        raise ValueError("benchmark_kind must be real or synthetic")
    if set(baseline_predictions) != set(BASELINE_NAMES):
        raise ValueError(f"baseline predictions must include exactly {BASELINE_NAMES}")
    if any(not _valid_sha256(value) for value in input_hashes.values()):
        raise ValueError("every input hash must be a lowercase SHA-256 digest")
    candidate_metrics = evaluate_predictions(candidate)
    teacher_metrics = evaluate_predictions(teacher)
    baseline_results = tuple(
        BaselineResult(
            name=name,
            brier=(metrics := evaluate_predictions(baseline_predictions[name])).brier,
            auroc=metrics.auroc,
            status="measured",
        )
        for name in BASELINE_NAMES
    )
    strongest = min(baseline_results, key=lambda item: (float(item.brier), item.name))
    interval = bootstrap_relative_brier_improvement(
        candidate,
        baseline_predictions[strongest.name],
        iterations=bootstrap_iterations,
        seed=seed,
    )
    provenance_payload = {
        "candidate_id": candidate_id,
        "benchmark_kind": benchmark_kind,
        "candidate_metrics": asdict(candidate_metrics),
        "teacher_metrics": asdict(teacher_metrics),
        "baselines": [asdict(item) for item in baseline_results],
        "resource": asdict(resource),
        "input_hashes": dict(sorted(input_hashes.items())),
        "bootstrap": asdict(interval),
    }
    provenance = hashlib.sha256(
        json.dumps(provenance_payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    evidence = CandidateEvidence(
        candidate_id=candidate_id,
        benchmark_kind=benchmark_kind,
        strongest_baseline=strongest.name,
        measurement_provenance_sha256=provenance,
        artifact_sha256=resource.artifact_sha256,
        relative_brier_improvement=interval.estimate,
        relative_brier_ci_low=interval.lower_95,
        ece=candidate_metrics.ece,
        distilled_brier_gap=candidate_metrics.brier - teacher_metrics.brier,
        distilled_auroc_gap=teacher_metrics.auroc - candidate_metrics.auroc,
        artifact_size_bytes=resource.artifact_size_bytes,
        incremental_ram_bytes=resource.incremental_ram_bytes,
        p95_inference_ms=resource.p95_inference_ms,
        reference_device=resource.reference_device,
    )
    return BenchmarkOutcome(
        evidence=evidence,
        candidate_metrics=candidate_metrics,
        teacher_metrics=teacher_metrics,
        baselines=baseline_results,
        input_hashes=dict(sorted(input_hashes.items())),
        bootstrap={
            "estimate": interval.estimate,
            "lower_95": interval.lower_95,
            "upper_95": interval.upper_95,
            "iterations": interval.iterations,
            "seed": interval.seed,
        },
    )
