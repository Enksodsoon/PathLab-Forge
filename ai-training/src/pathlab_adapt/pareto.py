"""Measured Pareto selection and fixed-order release gates."""

from __future__ import annotations

from dataclasses import asdict, dataclass

GATE_ORDER = (
    "real_benchmark",
    "relative_brier_improvement_at_least_5_percent",
    "bootstrap_95_ci_excludes_no_improvement",
    "ece_at_most_0_05",
    "distilled_brier_gap_at_most_0_01",
    "distilled_auroc_gap_at_most_0_02",
    "artifact_size_at_most_25mb",
    "incremental_ram_at_most_256mb",
    "p95_inference_at_most_150ms",
    "named_8gb_6core_reference_device",
)


@dataclass(frozen=True, slots=True)
class CandidateEvidence:
    candidate_id: str
    benchmark_kind: str
    strongest_baseline: str | None = None
    measurement_provenance_sha256: str | None = None
    artifact_sha256: str | None = None
    relative_brier_improvement: float | None = None
    relative_brier_ci_low: float | None = None
    ece: float | None = None
    distilled_brier_gap: float | None = None
    distilled_auroc_gap: float | None = None
    artifact_size_bytes: int | None = None
    incremental_ram_bytes: int | None = None
    p95_inference_ms: float | None = None
    reference_device: str | None = None

    def __post_init__(self) -> None:
        if not self.candidate_id:
            raise ValueError("candidate_id must be non-empty")
        if self.benchmark_kind not in {"real", "synthetic", "unmeasured"}:
            raise ValueError("benchmark_kind must be real, synthetic, or unmeasured")


@dataclass(frozen=True, slots=True)
class GateResult:
    gate_id: str
    status: str
    measured_value: str | float | int | bool | None
    threshold: str

    def __post_init__(self) -> None:
        if self.status not in {"passed", "failed", "unmeasured"}:
            raise ValueError("gate status must be passed, failed, or unmeasured")


@dataclass(frozen=True, slots=True)
class GateEvaluation:
    candidate_id: str
    gates: tuple[GateResult, ...]
    all_gates_passed: bool

    def to_dict(self) -> dict[str, object]:
        return {
            "candidate_id": self.candidate_id,
            "gates": [asdict(item) for item in self.gates],
            "all_gates_passed": self.all_gates_passed,
        }


def _gate(gate_id: str, value: object | None, threshold: str, passed: bool | None) -> GateResult:
    status = "unmeasured" if passed is None else "passed" if passed else "failed"
    return GateResult(gate_id, status, value, threshold)


def evaluate_gates(evidence: CandidateEvidence) -> GateEvaluation:
    device = (evidence.reference_device or "").lower().replace(" ", "")
    real_benchmark_provenance = (
        evidence.benchmark_kind == "real"
        and evidence.strongest_baseline
        in {"logistic_regression", "bkt", "gru", "ordinary_transformer"}
        and evidence.measurement_provenance_sha256 is not None
        and len(evidence.measurement_provenance_sha256) == 64
        and all(character in "0123456789abcdef" for character in evidence.measurement_provenance_sha256)
        and evidence.artifact_sha256 is not None
        and len(evidence.artifact_sha256) == 64
        and all(character in "0123456789abcdef" for character in evidence.artifact_sha256)
    )
    gates = (
        _gate(
            "real_benchmark",
            f"{evidence.benchmark_kind}:{evidence.strongest_baseline or 'unmeasured'}",
            "real benchmark with strongest baseline and hashed measurement provenance",
            real_benchmark_provenance,
        ),
        _gate(
            "relative_brier_improvement_at_least_5_percent",
            evidence.relative_brier_improvement,
            ">= 0.05",
            None if evidence.relative_brier_improvement is None else evidence.relative_brier_improvement >= 0.05,
        ),
        _gate(
            "bootstrap_95_ci_excludes_no_improvement",
            evidence.relative_brier_ci_low,
            "> 0.0",
            None if evidence.relative_brier_ci_low is None else evidence.relative_brier_ci_low > 0.0,
        ),
        _gate("ece_at_most_0_05", evidence.ece, "<= 0.05", None if evidence.ece is None else evidence.ece <= 0.05),
        _gate(
            "distilled_brier_gap_at_most_0_01",
            evidence.distilled_brier_gap,
            "<= 0.01",
            None if evidence.distilled_brier_gap is None else evidence.distilled_brier_gap <= 0.01,
        ),
        _gate(
            "distilled_auroc_gap_at_most_0_02",
            evidence.distilled_auroc_gap,
            "<= 0.02",
            None if evidence.distilled_auroc_gap is None else evidence.distilled_auroc_gap <= 0.02,
        ),
        _gate(
            "artifact_size_at_most_25mb",
            evidence.artifact_size_bytes,
            "<= 26214400 bytes",
            None if evidence.artifact_size_bytes is None else evidence.artifact_size_bytes <= 25 * 1024 * 1024,
        ),
        _gate(
            "incremental_ram_at_most_256mb",
            evidence.incremental_ram_bytes,
            "<= 268435456 bytes",
            None if evidence.incremental_ram_bytes is None else evidence.incremental_ram_bytes <= 256 * 1024 * 1024,
        ),
        _gate(
            "p95_inference_at_most_150ms",
            evidence.p95_inference_ms,
            "<= 150 ms",
            None if evidence.p95_inference_ms is None else evidence.p95_inference_ms <= 150.0,
        ),
        _gate(
            "named_8gb_6core_reference_device",
            evidence.reference_device,
            "named device with 8GB RAM and 6-core CPU",
            None if evidence.reference_device is None else bool(evidence.reference_device.strip()) and "8gb" in device and "6-core" in device,
        ),
    )
    if tuple(item.gate_id for item in gates) != GATE_ORDER:
        raise AssertionError("gate ordering changed")
    all_gates_passed = all(item.status == "passed" for item in gates)
    return GateEvaluation(evidence.candidate_id, gates, all_gates_passed)


def pareto_frontier(candidates: list[CandidateEvidence]) -> tuple[CandidateEvidence, ...]:
    """Return non-dominated fully measured candidates; no size is preferred a priori."""

    eligible = [item for item in candidates if evaluate_gates(item).all_gates_passed]
    frontier: list[CandidateEvidence] = []
    for candidate in eligible:
        vector = (
            -float(candidate.relative_brier_improvement),
            float(candidate.ece),
            float(candidate.artifact_size_bytes),
            float(candidate.incremental_ram_bytes),
            float(candidate.p95_inference_ms),
        )
        dominated = False
        for other in eligible:
            if other is candidate:
                continue
            other_vector = (
                -float(other.relative_brier_improvement),
                float(other.ece),
                float(other.artifact_size_bytes),
                float(other.incremental_ram_bytes),
                float(other.p95_inference_ms),
            )
            if all(left <= right for left, right in zip(other_vector, vector)) and any(
                left < right for left, right in zip(other_vector, vector)
            ):
                dominated = True
                break
        if not dominated:
            frontier.append(candidate)
    return tuple(sorted(frontier, key=lambda item: item.candidate_id))
