"""Honest, versioned TRACE artifact manifests."""

from __future__ import annotations

from dataclasses import asdict
from pathlib import Path
from typing import Any

from .baselines import BASELINE_NAMES, BaselineResult
from .io import sha256_file, write_json_atomic
from .ontology import CONTROL_ACTIONS
from .pareto import GateEvaluation

MANIFEST_VERSION = "pathlab-adapt-model-manifest-v1"


def build_manifest(
    *,
    model_id: str,
    dataset_kind: str,
    candidate_id: str,
    gates: GateEvaluation,
    baselines: list[BaselineResult],
    export_metadata: dict[str, Any] | None = None,
    license_ledger_sha256: str | None = None,
    artifact_sha256: str | None = None,
    artifact_size_bytes: int | None = None,
) -> dict[str, Any]:
    if dataset_kind not in {"real", "synthetic", "unmeasured"}:
        raise ValueError("dataset_kind must be real, synthetic, or unmeasured")
    baseline_by_name = {item.name: item for item in baselines}
    complete_baselines = all(
        name in baseline_by_name and baseline_by_name[name].status == "measured"
        for name in BASELINE_NAMES
    )
    valid_license_hash = (
        license_ledger_sha256 is not None
        and len(license_ledger_sha256) == 64
        and all(character in "0123456789abcdef" for character in license_ledger_sha256)
    )
    valid_artifact_hash = (
        artifact_sha256 is not None
        and len(artifact_sha256) == 64
        and all(character in "0123456789abcdef" for character in artifact_sha256)
    )
    if dataset_kind == "synthetic":
        claim_scope = "software_validation_only"
    else:
        claim_scope = "no_positive_performance_claim"
    return {
        "schema_version": MANIFEST_VERSION,
        "model_id": model_id,
        "candidate_id": candidate_id,
        "dataset_kind": dataset_kind,
        "approval_status": "not_approved",
        "claim_scope": claim_scope,
        "clinical_use": "prohibited",
        "human_benefit_claim": False,
        "delivery_mode": "fixed_order",
        "controller_actions": list(CONTROL_ACTIONS),
        "weights_frozen_on_release": True,
        "online_weight_update": False,
        "local_learner_context_update": True,
        "baseline_comparison_complete": complete_baselines,
        "approval_blockers": [
            reason
            for reason, blocked in (
                ("dataset_not_real", dataset_kind != "real"),
                ("release_fixed_order_only", True),
                ("prespecified_gate_failed_or_unmeasured", not gates.all_gates_passed),
                ("baseline_comparison_incomplete", not complete_baselines),
                ("license_ledger_hash_missing_or_invalid", not valid_license_hash),
                ("artifact_hash_missing_or_invalid", not valid_artifact_hash),
            )
            if blocked
        ],
        "baselines": [asdict(baseline_by_name[name]) for name in BASELINE_NAMES if name in baseline_by_name],
        "gate_evaluation": gates.to_dict(),
        "license_ledger_sha256": license_ledger_sha256,
        "artifact": {
            "sha256": artifact_sha256,
            "size_bytes": artifact_size_bytes,
        },
        "verified_provenance": None,
        "export": export_metadata
        or {
            "status": "not_exported",
            "format": "onnx",
            "quantization": "unmeasured",
            "artifact_sha256": None,
            "runtime": None,
        },
    }


def write_manifest(path: Path, manifest: dict[str, Any]) -> str:
    write_json_atomic(path, manifest)
    return sha256_file(path)
