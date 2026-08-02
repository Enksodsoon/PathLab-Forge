"""Offline command-line workflow for TRACE research artifacts."""

from __future__ import annotations

import argparse
import json
import sys
from contextlib import ExitStack
from itertools import zip_longest
from dataclasses import asdict
from pathlib import Path

from .adapters import EDNET_EVENT_CAP, EdNetAdapterConfig, adapt_ednet, adapt_oulad
from .baselines import BASELINE_NAMES, BaselineResult
from .benchmark import ResourceEvidence, benchmark_candidate
from .approval import (
    BENCHMARK_SCHEMA,
    PREDICTION_KEYS,
    issue_verified_manifest,
    validate_provenance_files,
)
from .evaluation import Prediction
from .io import sha256_file, write_json_atomic, write_jsonl_atomic
from .license import LicenseEntry, LicenseLedger
from .manifest import build_manifest, write_manifest
from .models import STUDENT_CONFIGS, TRACEFormerConfig, build_trace_former
from .pareto import CandidateEvidence, evaluate_gates
from .synthetic import SyntheticConfig, generate_synthetic_events


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="pathlab-adapt",
        description="Offline, non-clinical ADAPT research pipeline",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    synthetic = commands.add_parser("generate-synthetic")
    synthetic.add_argument("--output", required=True, type=Path)
    synthetic.add_argument("--seed", type=int, default=20260802)
    synthetic.add_argument("--learners", type=int, default=32)
    synthetic.add_argument("--events-per-learner", type=int, default=128)
    synthetic.add_argument("--concepts", type=int, default=8)

    oulad = commands.add_parser("adapt-oulad")
    oulad.add_argument("--student-vle", required=True, type=Path)
    oulad.add_argument("--output", required=True, type=Path)
    oulad.add_argument("--pseudonym-salt", required=True)

    ednet = commands.add_parser("adapt-ednet")
    ednet.add_argument("--root", required=True, type=Path)
    ednet.add_argument("--output", required=True, type=Path)
    ednet.add_argument("--pseudonym-salt", required=True)
    ednet.add_argument("--event-cap", type=int, default=EDNET_EVENT_CAP)
    ednet.add_argument("--license-ledger", required=True, type=Path)

    ledger = commands.add_parser("validate-license-ledger")
    ledger.add_argument("--input", required=True, type=Path)
    ledger.add_argument("--output", required=True, type=Path)

    benchmark = commands.add_parser("benchmark")
    benchmark.add_argument("--candidate-predictions", required=True, type=Path)
    benchmark.add_argument("--teacher-predictions", required=True, type=Path)
    benchmark.add_argument("--logistic-predictions", required=True, type=Path)
    benchmark.add_argument("--bkt-predictions", required=True, type=Path)
    benchmark.add_argument("--gru-predictions", required=True, type=Path)
    benchmark.add_argument("--transformer-predictions", required=True, type=Path)
    benchmark.add_argument("--resource-evidence", required=True, type=Path)
    benchmark.add_argument("--candidate-id", required=True)
    benchmark.add_argument("--bootstrap-iterations", type=int, default=1_000)
    benchmark.add_argument("--seed", type=int, default=20260802)
    benchmark.add_argument("--max-predictions", type=int, default=100_000)
    benchmark.add_argument("--model-artifact", required=True, type=Path)
    benchmark.add_argument("--license-ledger", required=True, type=Path)
    benchmark.add_argument("--dataset-manifest", required=True, type=Path)
    benchmark.add_argument("--split-manifest", required=True, type=Path)
    benchmark.add_argument(
        "--evaluation-protocol",
        choices=("learner_disjoint", "time_forward"),
        required=True,
    )
    benchmark.add_argument("--output", required=True, type=Path)

    evaluate = commands.add_parser("evaluate-gates")
    evaluate.add_argument("--evidence", required=True, type=Path)
    evaluate.add_argument("--output", required=True, type=Path)

    manifest = commands.add_parser("produce-manifest")
    manifest.add_argument("--evidence", type=Path)
    manifest.add_argument("--output", required=True, type=Path)
    manifest.add_argument("--model-id", required=True)
    manifest.add_argument("--baselines", type=Path)
    manifest.add_argument("--license-ledger", type=Path)
    manifest.add_argument("--export-metadata", type=Path)
    manifest.add_argument("--benchmark", type=Path)
    manifest.add_argument("--model-artifact", type=Path)
    manifest.add_argument("--dataset-manifest", type=Path)
    manifest.add_argument("--split-manifest", type=Path)

    export = commands.add_parser("export-onnx")
    export.add_argument("--checkpoint", required=True, type=Path)
    export.add_argument("--output", required=True, type=Path)
    export.add_argument(
        "--configuration",
        choices=("teacher", "student-3m", "student-8m", "student-15m"),
        required=True,
    )
    export.add_argument("--sample-context", type=int, default=16)
    export.add_argument("--metadata-output", required=True, type=Path)
    return parser


def _summary(path: Path, count: int, *, scope: str) -> dict[str, object]:
    return {
        "output": str(path.resolve()),
        "events": count,
        "sha256": sha256_file(path),
        "scope": scope,
    }


def _read_json(path: Path) -> object:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def _baselines(path: Path | None) -> list[BaselineResult]:
    if path is None:
        return [BaselineResult(name, None, None, "unmeasured") for name in BASELINE_NAMES]
    payload = _read_json(path)
    if not isinstance(payload, list):
        raise ValueError("baseline file must contain a JSON list")
    return [BaselineResult(**item) for item in payload]


def _read_predictions(path: Path, *, max_predictions: int) -> list[Prediction]:
    if not 1 <= max_predictions <= 100_000:
        raise ValueError("max-predictions must be in [1, 100,000]")
    rows: list[Prediction] = []
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            if len(rows) >= max_predictions:
                raise ValueError(f"prediction file {path} exceeds bounded cap {max_predictions}")
            payload = json.loads(line)
            if not isinstance(payload, dict):
                raise ValueError(f"prediction row {line_number} in {path} must be an object")
            rows.append(Prediction(**payload))
    return rows


def _read_aligned_predictions(
    paths: dict[str, Path], *, max_predictions: int
) -> dict[str, list[Prediction]]:
    """Stream six files in lockstep, rejecting misalignment before benchmarking."""

    if not 1 <= max_predictions <= 100_000:
        raise ValueError("max-predictions must be in [1, 100,000]")
    rows = {name: [] for name in paths}
    sentinel = object()
    with ExitStack() as stack:
        handles = {
            name: stack.enter_context(path.open(encoding="utf-8"))
            for name, path in paths.items()
        }
        iterators = [
            (line for line in handle if line.strip()) for handle in handles.values()
        ]
        for index, lines in enumerate(zip_longest(*iterators, fillvalue=sentinel)):
            if index >= max_predictions:
                raise ValueError(f"prediction artifacts exceed bounded cap {max_predictions}")
            if any(line is sentinel for line in lines):
                raise ValueError("ordered event alignment mismatch: prediction lengths differ")
            parsed: list[Prediction] = []
            for line in lines:
                payload = json.loads(str(line))
                if not isinstance(payload, dict):
                    raise ValueError("prediction row must be a JSON object")
                parsed.append(Prediction(**payload))
            expected = (parsed[0].event_id, parsed[0].learner_id, parsed[0].target)
            if any((item.event_id, item.learner_id, item.target) != expected for item in parsed[1:]):
                raise ValueError(f"ordered event alignment mismatch at row {index + 1}")
            for name, item in zip(paths, parsed):
                rows[name].append(item)
    if not rows[next(iter(paths))]:
        raise ValueError("prediction artifacts are empty")
    return rows


def _evidence_payload(payload: object) -> dict[str, object]:
    if not isinstance(payload, dict):
        raise ValueError("evidence must be a JSON object")
    nested = payload.get("evidence")
    if nested is not None:
        if not isinstance(nested, dict):
            raise ValueError("nested evidence must be a JSON object")
        return nested
    return payload


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "generate-synthetic":
            config = SyntheticConfig(
                seed=args.seed,
                learners=args.learners,
                events_per_learner=args.events_per_learner,
                concepts=args.concepts,
            )
            output, count = write_jsonl_atomic(
                args.output, (item.to_dict() for item in generate_synthetic_events(config))
            )
            print(json.dumps(_summary(output, count, scope="software_validation_only"), sort_keys=True))
            return 0

        if args.command == "adapt-oulad":
            output, count = write_jsonl_atomic(
                args.output,
                (item.to_dict() for item in adapt_oulad(args.student_vle, pseudonym_salt=args.pseudonym_salt)),
            )
            print(json.dumps(_summary(output, count, scope="real_public_benchmark"), sort_keys=True))
            return 0

        if args.command == "adapt-ednet":
            ledger_payload = _read_json(args.license_ledger)
            if not isinstance(ledger_payload, dict):
                raise ValueError("license ledger must be a JSON object")
            config = EdNetAdapterConfig(
                args.root,
                args.event_cap,
                args.pseudonym_salt,
                LicenseLedger.from_dict(ledger_payload),
            )
            output, count = write_jsonl_atomic(
                args.output, (item.to_dict() for item in adapt_ednet(config))
            )
            print(json.dumps(_summary(output, count, scope="license_gated_research"), sort_keys=True))
            return 0

        if args.command == "validate-license-ledger":
            payload = _read_json(args.input)
            if not isinstance(payload, dict) or not isinstance(payload.get("entries"), list):
                raise ValueError("license ledger input must contain an entries list")
            ledger = LicenseLedger(tuple(LicenseEntry(**entry) for entry in payload["entries"]))
            write_json_atomic(args.output, ledger.to_dict())
            print(json.dumps({"output": str(args.output.resolve()), "sha256": sha256_file(args.output)}, sort_keys=True))
            return 0

        if args.command == "benchmark":
            provenance = validate_provenance_files(
                license_ledger_path=args.license_ledger,
                dataset_manifest_path=args.dataset_manifest,
                split_manifest_path=args.split_manifest,
                evaluation_protocol=args.evaluation_protocol,
            )
            prediction_paths = {
                "candidate_predictions": args.candidate_predictions,
                "teacher_predictions": args.teacher_predictions,
                "logistic_regression_predictions": args.logistic_predictions,
                "bkt_predictions": args.bkt_predictions,
                "gru_predictions": args.gru_predictions,
                "ordinary_transformer_predictions": args.transformer_predictions,
            }
            resource_payload = _read_json(args.resource_evidence)
            if not isinstance(resource_payload, dict):
                raise ValueError("resource evidence must be a JSON object")
            predictions = _read_aligned_predictions(
                {
                    "candidate": args.candidate_predictions,
                    "teacher": args.teacher_predictions,
                    "logistic_regression": args.logistic_predictions,
                    "bkt": args.bkt_predictions,
                    "gru": args.gru_predictions,
                    "ordinary_transformer": args.transformer_predictions,
                },
                max_predictions=args.max_predictions,
            )
            model_sha = sha256_file(args.model_artifact)
            outcome = benchmark_candidate(
                predictions["candidate"],
                predictions["teacher"],
                {
                    name: predictions[name] for name in PREDICTION_KEYS[2:]
                },
                benchmark_kind=provenance.dataset_kind,
                candidate_id=args.candidate_id,
                resource=ResourceEvidence(
                    artifact_size_bytes=args.model_artifact.stat().st_size,
                    artifact_sha256=model_sha,
                    incremental_ram_bytes=int(resource_payload["incremental_ram_bytes"]),
                    p95_inference_ms=float(resource_payload["p95_inference_ms"]),
                    reference_device=str(resource_payload["reference_device"]),
                ),
                input_hashes={name: sha256_file(path) for name, path in prediction_paths.items()},
                bootstrap_iterations=args.bootstrap_iterations,
                seed=args.seed,
                expected_split_digest=provenance.split_test_digest,
            )
            prediction_path_map = {
                "candidate": args.candidate_predictions,
                "teacher": args.teacher_predictions,
                "logistic_regression": args.logistic_predictions,
                "bkt": args.bkt_predictions,
                "gru": args.gru_predictions,
                "ordinary_transformer": args.transformer_predictions,
            }
            record = {
                "schema_version": BENCHMARK_SCHEMA,
                "candidate_id": args.candidate_id,
                "model_id": args.candidate_id,
                "evaluation_protocol": provenance.evaluation_protocol,
                "seed": args.seed,
                "bootstrap_iterations": args.bootstrap_iterations,
                "provenance": {
                    "dataset_manifest_sha256": provenance.dataset_manifest_sha256,
                    "split_manifest_sha256": provenance.split_manifest_sha256,
                    "license_ledger_sha256": provenance.license_ledger_sha256,
                    "split_test_digest": provenance.split_test_digest,
                },
                "model_artifact": {"sha256": model_sha, "size_bytes": args.model_artifact.stat().st_size},
                "prediction_artifacts": {
                    name: {"path": str(path.resolve()), "sha256": sha256_file(path)}
                    for name, path in prediction_path_map.items()
                },
                "resource_evidence": {
                    "incremental_ram_bytes": int(resource_payload["incremental_ram_bytes"]),
                    "p95_inference_ms": float(resource_payload["p95_inference_ms"]),
                    "reference_device": str(resource_payload["reference_device"]),
                },
                "evidence": asdict(outcome.evidence),
                "baselines": [asdict(item) for item in outcome.baselines],
            }
            write_json_atomic(args.output, record)
            gate_result = evaluate_gates(outcome.evidence)
            print(json.dumps({"approved": gate_result.approved, "delivery_mode": gate_result.delivery_mode}, sort_keys=True))
            return 0

        if args.command == "evaluate-gates":
            payload = _read_json(args.evidence)
            result = evaluate_gates(CandidateEvidence(**_evidence_payload(payload)))
            write_json_atomic(args.output, result.to_dict())
            print(json.dumps({"approved": result.approved, "delivery_mode": result.delivery_mode}, sort_keys=True))
            return 0

        if args.command == "produce-manifest":
            if args.benchmark is not None:
                required = {
                    "model-artifact": args.model_artifact,
                    "license-ledger": args.license_ledger,
                    "dataset-manifest": args.dataset_manifest,
                    "split-manifest": args.split_manifest,
                }
                missing = [name for name, value in required.items() if value is None]
                if missing:
                    raise ValueError(f"verified manifest is missing arguments: {missing}")
                verified = issue_verified_manifest(
                    benchmark_path=args.benchmark,
                    model_path=args.model_artifact,
                    license_ledger_path=args.license_ledger,
                    dataset_manifest_path=args.dataset_manifest,
                    split_manifest_path=args.split_manifest,
                    output_path=args.output,
                )
                print(json.dumps({"approval_status": verified.payload["approval_status"], "sha256": verified.manifest_sha256}, sort_keys=True))
                return 0
            if args.evidence is None:
                raise ValueError("manual unapproved manifest requires --evidence")
            payload = _read_json(args.evidence)
            evidence = CandidateEvidence(**_evidence_payload(payload))
            gates = evaluate_gates(evidence)
            export_metadata = _read_json(args.export_metadata) if args.export_metadata else None
            if export_metadata is not None and not isinstance(export_metadata, dict):
                raise ValueError("export metadata must be a JSON object")
            baseline_results = _baselines(args.baselines)
            if args.baselines is None and isinstance(payload, dict) and isinstance(payload.get("baselines"), list):
                baseline_results = [BaselineResult(**item) for item in payload["baselines"]]
            manifest = build_manifest(
                model_id=args.model_id,
                dataset_kind=evidence.benchmark_kind,
                candidate_id=evidence.candidate_id,
                gates=gates,
                baselines=baseline_results,
                export_metadata=export_metadata,
                license_ledger_sha256=(sha256_file(args.license_ledger) if args.license_ledger else None),
                artifact_sha256=evidence.artifact_sha256,
                artifact_size_bytes=evidence.artifact_size_bytes,
            )
            digest = write_manifest(args.output, manifest)
            print(json.dumps({"approval_status": manifest["approval_status"], "sha256": digest}, sort_keys=True))
            return 0

        if args.command == "export-onnx":
            from .export import validate_export_paths

            validate_export_paths(
                args.output,
                args.metadata_output,
                model_source=args.checkpoint,
            )
            try:
                import torch
            except ImportError as error:
                raise RuntimeError("PyTorch is optional; install pathlab-ai-data[adapt-export]") from error
            from .export import export_onnx_int8

            configurations = {
                "teacher": TRACEFormerConfig.teacher(),
                "student-3m": STUDENT_CONFIGS[0],
                "student-8m": STUDENT_CONFIGS[1],
                "student-15m": STUDENT_CONFIGS[2],
            }
            config = configurations[args.configuration]
            if not 1 <= args.sample_context <= config.context_length:
                raise ValueError(f"sample-context must be in [1, {config.context_length}]")
            model = build_trace_former(config)
            state = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
            model.load_state_dict(state)
            metadata = export_onnx_int8(
                model,
                torch.zeros((1, args.sample_context), dtype=torch.long),
                args.output,
                metadata_output=args.metadata_output,
            )
            metadata["checkpoint_sha256"] = sha256_file(args.checkpoint)
            metadata["configuration"] = asdict(config)
            write_json_atomic(args.metadata_output, metadata)
            print(json.dumps(metadata, sort_keys=True))
            return 0
    except (OSError, ValueError, RuntimeError, TypeError, json.JSONDecodeError) as error:
        print(f"pathlab-adapt failed: {error}", file=sys.stderr)
        return 2
    raise AssertionError(f"unhandled command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
