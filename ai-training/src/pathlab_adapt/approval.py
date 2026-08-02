"""Verifier-only approval from cryptographically bound benchmark artifacts."""

from __future__ import annotations

import json
import math
import hashlib
import hmac
import secrets
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from .baselines import BASELINE_NAMES, BaselineResult
from .benchmark import ResourceEvidence, assert_prediction_alignment, benchmark_candidate
from .evaluation import Prediction
from .io import sha256_file
from .license import LicenseLedger, sha256_path
from .manifest import _APPROVAL_SEAL, _build_verified_manifest, build_manifest, write_manifest
from .pareto import CandidateEvidence, evaluate_gates
from .ontology import LearnerEvent
from .splits import learner_disjoint_split, time_forward_split

BENCHMARK_SCHEMA = "pathlab-adapt-verified-benchmark-v2"
DATASET_SCHEMA = "pathlab-adapt-dataset-manifest-v1"
SPLIT_SCHEMA = "pathlab-adapt-split-manifest-v1"
PREDICTION_KEYS = (
    "candidate",
    "teacher",
    "logistic_regression",
    "bkt",
    "gru",
    "ordinary_transformer",
)
_VERIFIED_RUNTIME_SEAL = object()


@dataclass(frozen=True, slots=True)
class SignedApprovalAttestation:
    payload: str
    signature_sha256: str


class ApprovalAuthority:
    """Ephemeral verifier authority; signatures cannot be supplied as JSON."""

    def __init__(self) -> None:
        self.__key = secrets.token_bytes(32)

    def verify(self, attestation: SignedApprovalAttestation) -> bool:
        expected = hmac.new(
            self.__key, attestation.payload.encode("utf-8"), hashlib.sha256
        ).hexdigest()
        if not hmac.compare_digest(expected, attestation.signature_sha256):
            return False
        try:
            payload = json.loads(attestation.payload)
        except json.JSONDecodeError:
            return False
        return (
            isinstance(payload, dict)
            and payload.get("schema_version") == "pathlab-adapt-runtime-attestation-v1"
            and payload.get("optional_validation_status") == "verified"
            and payload.get("all_gates_passed") is True
        )


@dataclass(frozen=True, slots=True)
class ValidatedProvenance:
    dataset_kind: str
    evaluation_protocol: str
    split_test_digest: str
    dataset_manifest_sha256: str
    split_manifest_sha256: str
    license_ledger_sha256: str
    data_redistribution_permitted: bool
    weights_redistribution_permitted: bool


@dataclass(frozen=True, slots=True)
class VerifiedApprovedManifest:
    payload: dict[str, Any]
    manifest_sha256: str
    _seal: object

    @property
    def verified_approved(self) -> bool:
        return (
            self._seal is _VERIFIED_RUNTIME_SEAL
            and self.payload.get("approval_status") == "approved"
        )


def _read_object(path: Path, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid {label}") from error
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must be a JSON object")
    return payload


def _resolve_artifact(manifest_path: Path, value: str) -> Path:
    candidate = Path(value)
    return candidate.resolve() if candidate.is_absolute() else (manifest_path.parent / candidate).resolve()


def _validate_split_protocol(
    *,
    name: str,
    record: dict[str, Any],
    manifest_path: Path,
    expected_event_count: int,
    expected_partitions: dict[str, tuple[LearnerEvent, ...]],
) -> str:
    partitions = record.get("partitions")
    if not isinstance(partitions, dict) or set(partitions) != {"train", "validation", "test"}:
        raise ValueError(f"{name} must bind train, validation, and test key artifacts")
    seen: set[str] = set()
    learners: dict[str, set[str]] = {partition: set() for partition in partitions}
    sequence_times: dict[str, dict[str, list[int]]] = {}
    total = 0
    test_digest = ""
    for partition in ("train", "validation", "test"):
        artifact = partitions[partition]
        if not isinstance(artifact, dict):
            raise ValueError(f"invalid {name} {partition} key artifact")
        key_path = _resolve_artifact(manifest_path, str(artifact.get("path", "")))
        if sha256_path(key_path) != artifact.get("sha256"):
            raise ValueError(f"{name} {partition} key checksum mismatch")
        digest = hashlib.sha256()
        count = 0
        with key_path.open(encoding="utf-8") as handle:
            for line in handle:
                if not line.strip():
                    continue
                row = json.loads(line)
                event_id = str(row.get("event_id", ""))
                learner_id = str(row.get("learner_id", ""))
                target = row.get("target")
                sequence_id = str(row.get("sequence_id", learner_id))
                timestamp_ms = int(row.get("timestamp_ms", 0))
                if not event_id or not learner_id or not isinstance(target, bool):
                    raise ValueError(f"invalid {name} split event key")
                if event_id in seen:
                    raise ValueError(f"duplicate event_id in {name} split manifest")
                seen.add(event_id)
                learners[partition].add(learner_id)
                sequence_times.setdefault(sequence_id, {}).setdefault(partition, []).append(timestamp_ms)
                digest.update(json.dumps([event_id, learner_id, target], separators=(",", ":")).encode("utf-8"))
                digest.update(b"\n")
                count += 1
        if count != artifact.get("count") or digest.hexdigest() != artifact.get("ordered_event_digest"):
            raise ValueError(f"{name} {partition} event count or digest mismatch")
        expected_digest = hashlib.sha256()
        for item in expected_partitions[partition]:
            expected_digest.update(
                json.dumps(
                    [item.event_id, item.learner_id, item.retention_target],
                    separators=(",", ":"),
                ).encode("utf-8")
            )
            expected_digest.update(b"\n")
        if count != len(expected_partitions[partition]) or digest.hexdigest() != expected_digest.hexdigest():
            raise ValueError(f"{name} {partition} keys were not derived from canonical events")
        total += count
        if partition == "test":
            test_digest = digest.hexdigest()
    if total != expected_event_count:
        raise ValueError(f"{name} split does not cover the dataset event count")
    if name == "learner_disjoint":
        if learners["train"] & learners["validation"] or learners["train"] & learners["test"] or learners["validation"] & learners["test"]:
            raise ValueError("learner leakage detected in learner-disjoint split manifest")
    else:
        order = ("train", "validation", "test")
        for values in sequence_times.values():
            nonempty = [partition for partition in order if partition in values]
            for left, right in zip(nonempty, nonempty[1:]):
                if left in values and right in values and max(values[left]) >= min(values[right]):
                    raise ValueError("time-order or equal-time leakage detected in time-forward split manifest")
    return test_digest


def validate_provenance_files(
    *,
    license_ledger_path: Path,
    dataset_manifest_path: Path,
    split_manifest_path: Path,
    evaluation_protocol: str,
) -> ValidatedProvenance:
    ledger_payload = _read_object(license_ledger_path, "license ledger")
    ledger = LicenseLedger.from_dict(ledger_payload)
    ledger_sha = sha256_file(license_ledger_path)

    dataset = _read_object(dataset_manifest_path, "dataset manifest")
    if dataset.get("schema_version") != DATASET_SCHEMA:
        raise ValueError("verified benchmark requires a versioned dataset manifest")
    if dataset.get("license_ledger_sha256") != ledger_sha:
        raise ValueError("dataset manifest license ledger checksum mismatch")
    event_artifact = dataset.get("event_artifact")
    sources = dataset.get("sources")
    if not isinstance(event_artifact, dict) or not isinstance(sources, list) or not sources:
        raise ValueError("dataset manifest artifacts are incomplete")
    event_path = _resolve_artifact(dataset_manifest_path, str(event_artifact.get("path", "")))
    if sha256_path(event_path) != event_artifact.get("sha256"):
        raise ValueError("dataset event artifact checksum mismatch")
    events: list[LearnerEvent] = []
    event_sources: set[str] = set()
    seen_event_ids: set[str] = set()
    with event_path.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            if len(events) >= 100_000:
                raise ValueError("canonical event artifact exceeds bounded approval limit 100,000")
            try:
                item = LearnerEvent(**json.loads(line))
            except (TypeError, ValueError, json.JSONDecodeError) as error:
                raise ValueError("canonical event artifact contains an invalid event") from error
            if not isinstance(item.retention_target, bool):
                raise ValueError("canonical approval events require boolean retention targets")
            if item.event_id in seen_event_ids:
                raise ValueError("canonical event artifact contains duplicate event_id")
            seen_event_ids.add(item.event_id)
            event_sources.add(item.source)
            events.append(item)
    event_count = dataset.get("event_count")
    if not isinstance(event_count, int) or event_count < 3:
        raise ValueError("dataset manifest requires a bounded positive event_count")
    if event_count != len(events):
        raise ValueError("dataset event_count does not match parsed canonical events")
    source_ids: set[str] = set()
    data_redistribution = True
    weights_redistribution = True
    for source in sources:
        if not isinstance(source, dict):
            raise ValueError("dataset source record must be an object")
        source_id = str(source.get("source_id", ""))
        source_path = _resolve_artifact(dataset_manifest_path, str(source.get("path", "")))
        entry = ledger.validate_source(source_id, source_path, require_derivative_models=True)
        if source.get("sha256") != entry.checksum_sha256:
            raise ValueError("dataset source checksum is not bound to the license ledger")
        source_ids.add(source_id)
        data_redistribution = data_redistribution and entry.data_redistribution_permitted
        weights_redistribution = weights_redistribution and entry.weights_redistribution_permitted
    canonical_real_sources = event_sources - {"synthetic"}
    if canonical_real_sources != source_ids:
        raise ValueError("canonical event sources do not match licensed dataset sources")
    derived_kind = "synthetic" if not canonical_real_sources else "real"
    if "ednet" in canonical_real_sources:
        weights_redistribution = False
    if dataset.get("dataset_kind") != derived_kind:
        raise ValueError("dataset kind does not match validated source provenance")

    dataset_sha = sha256_file(dataset_manifest_path)
    split = _read_object(split_manifest_path, "split manifest")
    if split.get("schema_version") != SPLIT_SCHEMA:
        raise ValueError("verified benchmark requires a versioned split manifest")
    if split.get("dataset_manifest_sha256") != dataset_sha:
        raise ValueError("split manifest dataset checksum mismatch")
    if not isinstance(split.get("seed"), int):
        raise ValueError("split manifest requires an integer seed")
    audit = split.get("leakage_audit")
    if not isinstance(audit, dict) or audit.get("status") != "passed" or audit.get("duplicate_event_ids") != 0:
        raise ValueError("split leakage audit is absent or failed")
    protocols = split.get("protocols")
    if not isinstance(protocols, dict) or not {"learner_disjoint", "time_forward"}.issubset(protocols):
        raise ValueError("both learner-disjoint and time-forward split protocols are required")
    protocol_test_digests: dict[str, str] = {}
    for name in ("learner_disjoint", "time_forward"):
        record = protocols[name]
        if not isinstance(record, dict) or not isinstance(record.get("seed"), int):
            raise ValueError(f"invalid {name} split record")
        validation_fraction = float(record.get("validation_fraction", 0.15))
        test_fraction = float(record.get("test_fraction", 0.15))
        derived_split = (
            learner_disjoint_split(
                events,
                seed=int(record["seed"]),
                validation_fraction=validation_fraction,
                test_fraction=test_fraction,
            )
            if name == "learner_disjoint"
            else time_forward_split(
                events,
                validation_fraction=validation_fraction,
                test_fraction=test_fraction,
            )
        )
        protocol_test_digests[name] = _validate_split_protocol(
            name=name,
            record=record,
            manifest_path=split_manifest_path,
            expected_event_count=event_count,
            expected_partitions=derived_split.as_dict(),
        )
    if evaluation_protocol not in {"learner_disjoint", "time_forward"}:
        raise ValueError("evaluation protocol is invalid")
    digest = protocol_test_digests[evaluation_protocol]
    if len(digest) != 64:
        raise ValueError("split ordered event digest is invalid")
    return ValidatedProvenance(
        dataset_kind=derived_kind,
        evaluation_protocol=evaluation_protocol,
        split_test_digest=digest,
        dataset_manifest_sha256=dataset_sha,
        split_manifest_sha256=sha256_file(split_manifest_path),
        license_ledger_sha256=ledger_sha,
        data_redistribution_permitted=data_redistribution,
        weights_redistribution_permitted=weights_redistribution,
    )


def _read_predictions(path: Path, *, limit: int = 100_000) -> list[Prediction]:
    rows: list[Prediction] = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            if len(rows) >= limit:
                raise ValueError(f"prediction artifact exceeds bounded verifier limit {limit}")
            payload = json.loads(line)
            rows.append(Prediction(**payload))
    return rows


def _verify_benchmark(
    *,
    benchmark_path: Path,
    model_path: Path,
    license_ledger_path: Path,
    dataset_manifest_path: Path,
    split_manifest_path: Path,
) -> tuple[dict[str, Any], CandidateEvidence, tuple[BaselineResult, ...], ValidatedProvenance]:
    record = _read_object(benchmark_path, "verified benchmark")
    if record.get("schema_version") != BENCHMARK_SCHEMA:
        raise ValueError("verified benchmark schema is required; loose manual evidence is not accepted")
    protocol = str(record.get("evaluation_protocol", ""))
    provenance = validate_provenance_files(
        license_ledger_path=license_ledger_path,
        dataset_manifest_path=dataset_manifest_path,
        split_manifest_path=split_manifest_path,
        evaluation_protocol=protocol,
    )
    bound = record.get("provenance")
    expected_bound = {
        "dataset_manifest_sha256": provenance.dataset_manifest_sha256,
        "split_manifest_sha256": provenance.split_manifest_sha256,
        "license_ledger_sha256": provenance.license_ledger_sha256,
        "split_test_digest": provenance.split_test_digest,
    }
    if bound != expected_bound:
        raise ValueError("verified benchmark provenance digest mismatch")
    model = record.get("model_artifact")
    actual_model_sha = sha256_file(model_path)
    if not isinstance(model, dict) or model.get("sha256") != actual_model_sha or model.get("size_bytes") != model_path.stat().st_size:
        raise ValueError("verified benchmark model hash or size mismatch")
    artifact_records = record.get("prediction_artifacts")
    if not isinstance(artifact_records, dict) or set(artifact_records) != set(PREDICTION_KEYS):
        raise ValueError("verified benchmark prediction artifacts are incomplete")
    paths: dict[str, Path] = {}
    hashes: dict[str, str] = {}
    for name in PREDICTION_KEYS:
        artifact = artifact_records[name]
        if not isinstance(artifact, dict):
            raise ValueError("invalid prediction artifact record")
        path = _resolve_artifact(benchmark_path, str(artifact.get("path", "")))
        digest = sha256_file(path)
        if digest != artifact.get("sha256"):
            raise ValueError(f"prediction artifact checksum mismatch: {name}")
        paths[name] = path
        hashes[name] = digest
    predictions = {name: _read_predictions(path) for name, path in paths.items()}
    assert_prediction_alignment(predictions, expected_split_digest=provenance.split_test_digest)
    resource_payload = record.get("resource_evidence")
    if not isinstance(resource_payload, dict):
        raise ValueError("resource evidence is missing")
    resource = ResourceEvidence(
        artifact_size_bytes=model_path.stat().st_size,
        artifact_sha256=actual_model_sha,
        incremental_ram_bytes=int(resource_payload["incremental_ram_bytes"]),
        p95_inference_ms=float(resource_payload["p95_inference_ms"]),
        reference_device=str(resource_payload["reference_device"]),
    )
    outcome = benchmark_candidate(
        predictions["candidate"],
        predictions["teacher"],
        {name: predictions[name] for name in PREDICTION_KEYS[2:]},
        benchmark_kind=provenance.dataset_kind,
        candidate_id=str(record.get("candidate_id", "")),
        resource=resource,
        input_hashes=hashes,
        bootstrap_iterations=int(record.get("bootstrap_iterations", 0)),
        seed=int(record.get("seed", 0)),
        expected_split_digest=provenance.split_test_digest,
    )
    stored_evidence = record.get("evidence")
    if stored_evidence != asdict(outcome.evidence):
        raise ValueError("verified benchmark evidence does not match recomputed metrics")
    stored_baselines = record.get("baselines")
    if stored_baselines != [asdict(item) for item in outcome.baselines]:
        raise ValueError("verified benchmark baseline metrics do not match recomputation")
    numeric = [
        outcome.evidence.relative_brier_improvement,
        outcome.evidence.relative_brier_ci_low,
        outcome.evidence.ece,
        outcome.evidence.distilled_brier_gap,
        outcome.evidence.distilled_auroc_gap,
    ]
    if any(value is None or not math.isfinite(float(value)) for value in numeric):
        raise ValueError("verified benchmark metrics must be finite")
    return record, outcome.evidence, outcome.baselines, provenance


def issue_verified_manifest(
    *,
    benchmark_path: Path,
    model_path: Path,
    license_ledger_path: Path,
    dataset_manifest_path: Path,
    split_manifest_path: Path,
    output_path: Path,
    runtime_attestation: SignedApprovalAttestation | None = None,
    approval_authority: ApprovalAuthority | None = None,
) -> VerifiedApprovedManifest:
    initial = _read_object(benchmark_path, "verified benchmark")
    if initial.get("schema_version") != BENCHMARK_SCHEMA:
        raise ValueError(
            "verified benchmark schema is required; loose manual evidence is not accepted"
        )
    try:
        record, evidence, baselines, provenance = _verify_benchmark(
            benchmark_path=benchmark_path,
            model_path=model_path,
            license_ledger_path=license_ledger_path,
            dataset_manifest_path=dataset_manifest_path,
            split_manifest_path=split_manifest_path,
        )
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        evidence = CandidateEvidence(
            candidate_id=str(initial.get("candidate_id", "unverified")) or "unverified",
            benchmark_kind="unmeasured",
        )
        gates = evaluate_gates(evidence)
        baselines = tuple(
            BaselineResult(name, None, None, "unmeasured") for name in BASELINE_NAMES
        )
        payload = build_manifest(
            model_id=str(initial.get("model_id", evidence.candidate_id)),
            dataset_kind="unmeasured",
            candidate_id=evidence.candidate_id,
            gates=gates,
            baselines=list(baselines),
            artifact_sha256=sha256_file(model_path) if model_path.is_file() else None,
            artifact_size_bytes=model_path.stat().st_size if model_path.is_file() else None,
        )
        payload["runtime_validation"] = {
            "status": "unverified",
            "reason": "canonical_or_runtime_evidence_invalid",
        }
        payload["verification_errors"] = [str(error)]
        payload["redistribution_policy"] = {
            "data_permitted": False,
            "weights_permitted": False,
        }
        digest = write_manifest(output_path, payload)
        return VerifiedApprovedManifest(payload, digest, None)

    gates = evaluate_gates(evidence)
    expected_attestation = {
        "schema_version": "pathlab-adapt-runtime-attestation-v1",
        "benchmark_sha256": sha256_file(benchmark_path),
        "model_sha256": sha256_file(model_path),
        "dataset_manifest_sha256": provenance.dataset_manifest_sha256,
        "split_manifest_sha256": provenance.split_manifest_sha256,
        "license_ledger_sha256": provenance.license_ledger_sha256,
        "measurement_provenance_sha256": str(evidence.measurement_provenance_sha256),
        "optional_validation_status": "verified",
        "all_gates_passed": gates.approved,
    }
    authorized = False
    if (
        isinstance(runtime_attestation, SignedApprovalAttestation)
        and isinstance(approval_authority, ApprovalAuthority)
        and approval_authority.verify(runtime_attestation)
    ):
        try:
            authorized = json.loads(runtime_attestation.payload) == expected_attestation
        except json.JSONDecodeError:
            authorized = False
    common = dict(
        model_id=str(record.get("model_id", evidence.candidate_id)),
        dataset_kind=provenance.dataset_kind,
        candidate_id=evidence.candidate_id,
        gates=gates,
        baselines=list(baselines),
        export_metadata=record.get("export_metadata") if isinstance(record.get("export_metadata"), dict) else None,
        license_ledger_sha256=provenance.license_ledger_sha256,
        artifact_sha256=sha256_file(model_path),
        artifact_size_bytes=model_path.stat().st_size,
    )
    verified_provenance = {
            "benchmark_sha256": sha256_file(benchmark_path),
            "dataset_manifest_sha256": provenance.dataset_manifest_sha256,
            "split_manifest_sha256": provenance.split_manifest_sha256,
            "license_ledger_sha256": provenance.license_ledger_sha256,
            "measurement_provenance_sha256": str(evidence.measurement_provenance_sha256),
    }
    if authorized:
        payload = _build_verified_manifest(
            **common,
            verified_provenance=verified_provenance,
            approval_seal=_APPROVAL_SEAL,
        )
        seal: object | None = _VERIFIED_RUNTIME_SEAL
    else:
        payload = build_manifest(**common)
        payload["approval_blockers"].append("verifier_owned_runtime_attestation_required")
        seal = None
    payload["runtime_validation"] = {
        "status": "verified" if authorized else "unverified",
        "reason": "verifier_owned_runtime" if authorized else "optional_runtime_or_attestation_unavailable",
    }
    payload["redistribution_policy"] = {
        "data_permitted": provenance.data_redistribution_permitted,
        "weights_permitted": provenance.weights_redistribution_permitted,
    }
    if not provenance.weights_redistribution_permitted and isinstance(payload.get("export"), dict):
        payload["export"]["redistribution_permitted"] = False
    digest = write_manifest(output_path, payload)
    return VerifiedApprovedManifest(payload, digest, seal)
