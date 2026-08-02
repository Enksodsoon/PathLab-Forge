from __future__ import annotations

import csv
import hashlib
import json
import tempfile
import unittest
from dataclasses import asdict, replace
from pathlib import Path

from pathlab_adapt.approval import (
    BENCHMARK_SCHEMA,
    issue_verified_manifest,
    verify_and_produce_manifest,
)
from pathlab_adapt.benchmark import (
    ResourceEvidence,
    assert_prediction_alignment,
    benchmark_candidate,
    ordered_event_digest,
)
from pathlab_adapt.distillation import DistillationConfig, multitask_distillation_loss
from pathlab_adapt.evaluation import (
    Prediction,
    bootstrap_relative_brier_improvement,
    evaluate_predictions,
)
from pathlab_adapt.export import validate_export_paths
from pathlab_adapt.io import sha256_file, write_json_atomic, write_jsonl_atomic
from pathlab_adapt.license import LicenseEntry, LicenseLedger, sha256_path
from pathlab_adapt.manifest import write_manifest
from pathlab_adapt.ontology import LearnerEvent
from pathlab_adapt.ood import ControllerPolicy, UncertaintySignal
from pathlab_adapt.optional_validation import run_optional_behavior_checks
from pathlab_adapt.splits import learner_disjoint_split, time_forward_split


def make_event(
    learner: str,
    event_index: int,
    *,
    timestamp_ms: int | None = None,
    sequence_id: str | None = None,
) -> LearnerEvent:
    return LearnerEvent(
        event_id=f"{learner}-{event_index}",
        learner_id=learner,
        sequence_id=sequence_id or learner,
        timestamp_ms=event_index if timestamp_ms is None else timestamp_ms,
        sequence_index=event_index,
        task_id=f"task-{event_index}",
        concept_id="concept",
        action="attempt",
        source="fixture",
        retention_target=event_index % 2 == 0,
    )


class CriticalApprovalTests(unittest.TestCase):
    def test_release_contains_no_private_or_public_manifest_approval_capability(self) -> None:
        from pathlab_adapt import manifest

        self.assertFalse(hasattr(manifest, "_APPROVAL_SEAL"))
        self.assertFalse(hasattr(manifest, "_build_verified_manifest"))

    def test_cli_and_verifier_share_prediction_provenance_vocabulary(self) -> None:
        from pathlab_adapt import approval, benchmark, cli

        for consumer in (approval, cli):
            self.assertIs(
                consumer.prediction_artifact_hashes,
                benchmark.prediction_artifact_hashes,
            )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {}
            for name in benchmark.PREDICTION_KEYS:
                path = root / f"{name}.jsonl"
                path.write_text(f"{name}\n", encoding="utf-8")
                paths[name] = path
            hashes = benchmark.prediction_artifact_hashes(paths)
        self.assertEqual(tuple(hashes), benchmark.PREDICTION_KEYS)

    def test_predictions_require_identical_ordered_event_keys_and_targets(self) -> None:
        candidate = [Prediction("e1", "learner", True, 0.9)]
        teacher = [Prediction("different", "learner", True, 0.9)]
        with self.assertRaisesRegex(ValueError, "ordered event alignment"):
            assert_prediction_alignment(
                {"candidate": candidate, "teacher": teacher},
                expected_split_digest="a" * 64,
            )

    def test_loose_manual_json_cannot_issue_an_approved_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            benchmark = root / "benchmark.json"
            model = root / "model.onnx"
            ledger = root / "ledger.json"
            dataset = root / "dataset.json"
            split = root / "split.json"
            output = root / "manifest.json"
            model.write_bytes(b"not-a-verified-model")
            forged = {
                "schema_version": "pathlab-adapt-benchmark-v1",
                "evidence": {
                    "candidate_id": "forged",
                    "benchmark_kind": "real",
                    "strongest_baseline": "ordinary_transformer",
                    "measurement_provenance_sha256": "a" * 64,
                    "artifact_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
                    "relative_brier_improvement": 1.0,
                    "relative_brier_ci_low": 1.0,
                    "ece": 0.0,
                    "distilled_brier_gap": 0.0,
                    "distilled_auroc_gap": 0.0,
                    "artifact_size_bytes": model.stat().st_size,
                    "incremental_ram_bytes": 1,
                    "p95_inference_ms": 1,
                    "reference_device": "forged / 8GB RAM / 6-core CPU",
                },
            }
            benchmark.write_text(json.dumps(forged), encoding="utf-8")
            for path in (ledger, dataset, split):
                path.write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "verified benchmark"):
                issue_verified_manifest(
                    benchmark_path=benchmark,
                    model_path=model,
                    license_ledger_path=ledger,
                    dataset_manifest_path=dataset,
                    split_manifest_path=split,
                    output_path=output,
                )
            self.assertFalse(output.exists())

    def test_fake_model_events_splits_predictions_and_resources_never_approve(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "oulad-source.csv"
            source.write_text("authorized source fixture", encoding="utf-8")
            ledger = LicenseLedger(
                (
                    LicenseEntry(
                        source_id="oulad",
                        source_url="https://example.test/oulad",
                        license_name="fixture",
                        permitted_use="research",
                        redistribution="permitted",
                        derivative_model_restrictions="permitted",
                        retrieval_date="2026-08-02",
                        checksum_sha256=sha256_path(source),
                        research_use_permitted=True,
                        derivative_models_permitted=True,
                        data_redistribution_permitted=True,
                        weights_redistribution_permitted=True,
                    ),
                )
            )
            ledger_path = root / "license-ledger.json"
            write_json_atomic(ledger_path, ledger.to_dict())
            events_path = root / "events.jsonl"
            events_path.write_text("fixture event artifact\n", encoding="utf-8")
            dataset_path = root / "dataset-manifest.json"
            write_json_atomic(
                dataset_path,
                {
                    "schema_version": "pathlab-adapt-dataset-manifest-v1",
                    "dataset_kind": "real",
                    "license_ledger_sha256": sha256_file(ledger_path),
                    "event_artifact": {"path": str(events_path), "sha256": sha256_path(events_path)},
                    "event_count": 42,
                    "sources": [{"source_id": "oulad", "path": str(source), "sha256": sha256_path(source)}],
                },
            )
            candidate = [
                Prediction(f"event-{i}", f"learner-{i}", bool(i % 2), 0.96 if i % 2 else 0.04)
                for i in range(40)
            ]
            teacher = [replace(item, probability=0.97 if item.target else 0.03) for item in candidate]
            baseline_probabilities = {
                "logistic_regression": 0.70,
                "bkt": 0.65,
                "gru": 0.68,
                "ordinary_transformer": 0.72,
            }
            baselines = {
                name: [replace(item, probability=value if item.target else 1 - value) for item in candidate]
                for name, value in baseline_probabilities.items()
            }
            predictions = {"candidate": candidate, "teacher": teacher, **baselines}
            prediction_paths: dict[str, Path] = {}
            for name, rows in predictions.items():
                path = root / f"{name}.jsonl"
                write_jsonl_atomic(path, (asdict(item) for item in rows))
                prediction_paths[name] = path
            split_keys = root / "test-keys.jsonl"
            write_jsonl_atomic(
                split_keys,
                (
                    {
                        "event_id": item.event_id,
                        "learner_id": item.learner_id,
                        "target": item.target,
                        "sequence_id": item.learner_id,
                        "timestamp_ms": 2,
                    }
                    for item in candidate
                ),
            )
            split_digest = ordered_event_digest(candidate)
            split_path = root / "split-manifest.json"
            test_key_record = {
                "path": str(split_keys),
                "sha256": sha256_path(split_keys),
                "ordered_event_digest": split_digest,
                "count": 40,
            }
            train_keys = root / "train-keys.jsonl"
            validation_keys = root / "validation-keys.jsonl"
            write_jsonl_atomic(
                train_keys,
                ({"event_id": "train-event", "learner_id": "train-learner", "target": False, "sequence_id": "train-sequence", "timestamp_ms": 0},),
            )
            write_jsonl_atomic(
                validation_keys,
                ({"event_id": "validation-event", "learner_id": "validation-learner", "target": True, "sequence_id": "validation-sequence", "timestamp_ms": 1},),
            )
            train_record = {
                "path": str(train_keys),
                "sha256": sha256_path(train_keys),
                "ordered_event_digest": hashlib.sha256(b'["train-event","train-learner",false]\n').hexdigest(),
                "count": 1,
            }
            validation_record = {
                "path": str(validation_keys),
                "sha256": sha256_path(validation_keys),
                "ordered_event_digest": hashlib.sha256(b'["validation-event","validation-learner",true]\n').hexdigest(),
                "count": 1,
            }
            partitions = {"train": train_record, "validation": validation_record, "test": test_key_record}
            write_json_atomic(
                split_path,
                {
                    "schema_version": "pathlab-adapt-split-manifest-v1",
                    "dataset_manifest_sha256": sha256_file(dataset_path),
                    "seed": 8,
                    "leakage_audit": {"status": "passed", "duplicate_event_ids": 0},
                    "protocols": {
                        "learner_disjoint": {"seed": 8, "partitions": partitions},
                        "time_forward": {"seed": 8, "partitions": partitions},
                    },
                },
            )
            model = root / "student.onnx"
            model.write_bytes(b"measured-model-fixture")
            resource = ResourceEvidence(
                artifact_size_bytes=model.stat().st_size,
                artifact_sha256=sha256_file(model),
                incremental_ram_bytes=100_000_000,
                p95_inference_ms=80.0,
                reference_device="Forge-PC / 8GB RAM / 6-core CPU",
            )
            hashes = {name: sha256_file(path) for name, path in prediction_paths.items()}
            outcome = benchmark_candidate(
                candidate,
                teacher,
                baselines,
                benchmark_kind="real",
                candidate_id="verified-student",
                resource=resource,
                input_hashes=hashes,
                bootstrap_iterations=100,
                seed=8,
                expected_split_digest=split_digest,
            )
            benchmark_path = root / "benchmark.json"
            write_json_atomic(
                benchmark_path,
                {
                    "schema_version": BENCHMARK_SCHEMA,
                    "candidate_id": "verified-student",
                    "model_id": "verified-student",
                    "evaluation_protocol": "learner_disjoint",
                    "seed": 8,
                    "bootstrap_iterations": 100,
                    "provenance": {
                        "dataset_manifest_sha256": sha256_file(dataset_path),
                        "split_manifest_sha256": sha256_file(split_path),
                        "license_ledger_sha256": sha256_file(ledger_path),
                        "split_test_digest": split_digest,
                    },
                    "model_artifact": {"sha256": sha256_file(model), "size_bytes": model.stat().st_size},
                    "prediction_artifacts": {
                        name: {"path": str(path), "sha256": sha256_file(path)}
                        for name, path in prediction_paths.items()
                    },
                    "resource_evidence": {
                        "incremental_ram_bytes": resource.incremental_ram_bytes,
                        "p95_inference_ms": resource.p95_inference_ms,
                        "reference_device": resource.reference_device,
                    },
                    "evidence": asdict(outcome.evidence),
                    "baselines": [asdict(item) for item in outcome.baselines],
                },
            )
            manifest_path = root / "manifest.json"
            verified = verify_and_produce_manifest(
                benchmark_path=benchmark_path,
                model_path=model,
                license_ledger_path=ledger_path,
                dataset_manifest_path=dataset_path,
                split_manifest_path=split_path,
                output_path=manifest_path,
            )
            self.assertEqual(verified.payload["approval_status"], "not_approved")
            self.assertIn("all_gates_passed", verified.payload["gate_evaluation"])
            self.assertNotIn("approved", verified.payload["gate_evaluation"])
            self.assertNotIn("delivery_mode", verified.payload["gate_evaluation"])
            policy = ControllerPolicy(max_ood_score=0.5, max_uncertainty=0.4)
            self.assertEqual(
                policy.decide(
                    UncertaintySignal(0.1, 0.1), approved_manifest=verified
                ).delivery_mode,
                "fixed_order",
            )
            model.write_bytes(b"tampered")
            tampered = issue_verified_manifest(
                benchmark_path=benchmark_path,
                model_path=model,
                license_ledger_path=ledger_path,
                dataset_manifest_path=dataset_path,
                split_manifest_path=split_path,
                output_path=root / "tampered-manifest.json",
            )
            self.assertEqual(tampered.payload["approval_status"], "not_approved")


class ChronologyAndSplitTests(unittest.TestCase):
    def test_oulad_event_supports_negative_relative_time_and_separate_presentation_sequence(self) -> None:
        item = make_event("learner", 0, timestamp_ms=-86_400_000, sequence_id="AAA:2013J")
        self.assertEqual(item.timestamp_ms, -86_400_000)
        self.assertEqual(item.sequence_id, "AAA:2013J")

    def test_equal_timestamp_group_is_never_split(self) -> None:
        events = [
            make_event("learner", 0, timestamp_ms=0),
            make_event("learner", 1, timestamp_ms=1),
            make_event("learner", 2, timestamp_ms=1),
            make_event("learner", 3, timestamp_ms=2),
            make_event("learner", 4, timestamp_ms=3),
        ]
        result = time_forward_split(events, validation_fraction=0.2, test_fraction=0.2)
        partitions = {
            item.event_id: name
            for name, rows in result.as_dict().items()
            for item in rows
        }
        self.assertEqual(partitions["learner-1"], partitions["learner-2"])

    def test_global_duplicate_event_ids_are_rejected_before_split(self) -> None:
        events = [make_event(f"learner-{index}", 0) for index in range(3)]
        events[2] = replace(events[2], event_id=events[0].event_id)
        with self.assertRaisesRegex(ValueError, "duplicate event_id"):
            learner_disjoint_split(events)


class EfficientEvaluationTests(unittest.TestCase):
    def test_rank_auroc_handles_large_input_without_pairwise_comparison(self) -> None:
        rows = [
            Prediction(f"event-{index}", f"learner-{index // 4}", bool(index % 2), (index + 1) / 10_001)
            for index in range(10_000)
        ]
        metrics = evaluate_predictions(rows)
        self.assertEqual(metrics.observations, 10_000)
        self.assertTrue(0.0 <= metrics.auroc <= 1.0)

    def test_bootstrap_iterations_are_hard_bounded(self) -> None:
        rows = [
            Prediction(f"event-{index}", f"learner-{index}", bool(index % 2), 0.8 if index % 2 else 0.2)
            for index in range(20)
        ]
        with self.assertRaisesRegex(ValueError, "10,000"):
            bootstrap_relative_brier_improvement(
                rows, [replace(item, probability=0.6 if item.target else 0.4) for item in rows], iterations=10_001
            )

    def test_bootstrap_learner_iteration_operations_are_hard_bounded(self) -> None:
        rows = [
            Prediction(f"event-{index}", f"learner-{index}", bool(index % 2), 0.8 if index % 2 else 0.2)
            for index in range(2_001)
        ]
        with self.assertRaisesRegex(ValueError, "operation budget"):
            bootstrap_relative_brier_improvement(
                rows,
                [replace(item, probability=0.6 if item.target else 0.4) for item in rows],
                iterations=1_000,
            )


class LicenseControllerAndOptionalTests(unittest.TestCase):
    def test_license_entry_validates_actual_artifact_and_structured_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "source.csv"
            artifact.write_text("fixture", encoding="utf-8")
            entry = LicenseEntry(
                source_id="ednet",
                source_url="https://example.test/ednet",
                license_name="research terms",
                permitted_use="research",
                redistribution="prohibited",
                derivative_model_restrictions="restricted",
                retrieval_date="2026-08-02",
                checksum_sha256=sha256_path(artifact),
                research_use_permitted=True,
                derivative_models_permitted=True,
                data_redistribution_permitted=False,
                weights_redistribution_permitted=False,
            )
            ledger = LicenseLedger((entry,))
            ledger.validate_source("ednet", artifact, require_derivative_models=True)
            with self.assertRaisesRegex(ValueError, "checksum"):
                ledger.validate_source("ednet", artifact.with_name("missing.csv"))

    def test_controller_is_fixed_order_only_and_thresholds_are_bounded(self) -> None:
        with self.assertRaisesRegex(ValueError, "max_ood_score"):
            ControllerPolicy(max_ood_score=1.1, max_uncertainty=0.4)
        policy = ControllerPolicy(max_ood_score=0.5, max_uncertainty=0.4)
        decision = policy.decide(UncertaintySignal(ood_score=0.1, uncertainty=0.1))
        self.assertEqual(decision.delivery_mode, "fixed_order")
        self.assertEqual(decision.reason, "fixed_order_only_release")
        fake = type("FakeApproval", (), {"verified_approved": True})()
        forged = policy.decide(
            UncertaintySignal(ood_score=0.1, uncertainty=0.1),
            approved_manifest=fake,
        )
        self.assertEqual(forged.delivery_mode, "fixed_order")

    def test_constructed_objects_cannot_enable_adaptive_delivery(self) -> None:
        from pathlab_adapt import approval

        self.assertFalse(hasattr(approval, "ApprovalAuthority"))
        self.assertFalse(hasattr(approval, "SignedApprovalAttestation"))
        attestation = type("ConstructedAttestation", (), {"all_gates_passed": True})()
        authority = type("ConstructedAuthority", (), {"verify": lambda self, value: True})()
        decision = ControllerPolicy(0.5, 0.4).decide(
            UncertaintySignal(0.1, 0.1),
            approval_attestation=attestation,
            approval_authority=authority,
        )
        self.assertEqual(decision.delivery_mode, "fixed_order")

    def test_distillation_rejects_missing_prespecified_head_before_optional_runtime(self) -> None:
        outputs = {name: object() for name in ("retention", "effort", "calibration")}
        with self.assertRaisesRegex(ValueError, "exactly"):
            multitask_distillation_loss(outputs, outputs, outputs, DistillationConfig())

    def test_export_rejects_resolved_model_metadata_or_staging_collisions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "artifact.onnx"
            with self.assertRaisesRegex(ValueError, "distinct"):
                validate_export_paths(target, target)
            with self.assertRaisesRegex(ValueError, "distinct"):
                validate_export_paths(
                    target, Path(directory) / "metadata.json", model_source=target
                )

    def test_optional_behavior_job_is_verified_or_explicitly_unverified(self) -> None:
        result = run_optional_behavior_checks()
        self.assertIn(result["status"], {"verified", "unverified_missing_dependencies"})
        self.assertIn("torch", result["checks"])


if __name__ == "__main__":
    unittest.main()
