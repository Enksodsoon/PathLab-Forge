from __future__ import annotations

import csv
import hashlib
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from pathlab_adapt.adapters import EdNetAdapterConfig, adapt_ednet, adapt_oulad
from pathlab_adapt.baselines import BASELINE_NAMES, BaselineResult, BktBaseline
from pathlab_adapt.benchmark import ResourceEvidence, benchmark_candidate
from pathlab_adapt.cli import main
from pathlab_adapt.evaluation import (
    Prediction,
    TemperatureCalibrator,
    bootstrap_relative_brier_improvement,
    evaluate_predictions,
)
from pathlab_adapt.license import (
    LicenseEntry,
    LicenseLedger,
    TraceDistribution,
    sha256_path,
)
from pathlab_adapt.manifest import build_manifest, write_manifest
from pathlab_adapt.models import (
    STUDENT_CONFIGS,
    TRACEFormerConfig,
    build_trace_former,
    optional_torch_available,
)
from pathlab_adapt.ontology import CONTROL_ACTIONS, LearnerEvent
from pathlab_adapt.ood import ControllerPolicy, UncertaintySignal
from pathlab_adapt.pareto import GATE_ORDER, CandidateEvidence, evaluate_gates
from pathlab_adapt.splits import learner_disjoint_split, time_forward_split
from pathlab_adapt.synthetic import SyntheticConfig, generate_synthetic_events


def event(learner: str, index: int, *, timestamp_ms: int | None = None) -> LearnerEvent:
    return LearnerEvent(
        event_id=f"{learner}-{index}",
        learner_id=learner,
        timestamp_ms=timestamp_ms if timestamp_ms is not None else index * 1_000,
        sequence_index=index,
        task_id=f"task-{index % 3}",
        concept_id=f"concept-{index % 2}",
        action="attempt",
        correct=index % 2 == 0,
        effort=0.5,
        hint_used=index % 3 == 0,
        confidence=0.7,
        source_checked=index % 4 == 0,
        retention_target=index % 2 == 0,
        source="fixture",
    )


class OntologyAndSyntheticTests(unittest.TestCase):
    def test_control_actions_are_exact_and_stable(self) -> None:
        self.assertEqual(
            CONTROL_ACTIONS,
            (
                "continue",
                "retrieve",
                "schedule_review",
                "offer_hint",
                "ask_confidence",
                "ask_source_check",
                "pause",
            ),
        )

    def test_event_validation_rejects_noncanonical_values(self) -> None:
        with self.assertRaisesRegex(ValueError, "confidence"):
            replace(event("learner", 0), confidence=1.1)
        with self.assertRaisesRegex(ValueError, "learner_id"):
            replace(event("learner", 0), learner_id="")

    def test_synthetic_generator_is_deterministic_and_exercises_all_signals(self) -> None:
        config = SyntheticConfig(seed=44, learners=3, events_per_learner=30)
        first = list(generate_synthetic_events(config))
        second = list(generate_synthetic_events(config))
        self.assertEqual(first, second)
        self.assertEqual(len(first), 90)
        self.assertTrue(any(item.hint_used for item in first))
        self.assertTrue(any(item.source_checked for item in first))
        self.assertTrue(any(not item.retention_target for item in first))
        self.assertTrue(all(item.source == "synthetic" for item in first))
        self.assertTrue(all(0.0 <= item.effort <= 1.0 for item in first))
        self.assertTrue(all(0.0 <= item.confidence <= 1.0 for item in first))


class AdapterAndLicenseTests(unittest.TestCase):
    def test_oulad_adapter_streams_canonical_events(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "studentVle.csv"
            with source.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=(
                        "code_module",
                        "code_presentation",
                        "id_student",
                        "id_site",
                        "date",
                        "sum_click",
                    ),
                )
                writer.writeheader()
                writer.writerow(
                    {
                        "code_module": "AAA",
                        "code_presentation": "2013J",
                        "id_student": "42",
                        "id_site": "7",
                        "date": "3",
                        "sum_click": "5",
                    }
                )
                writer.writerow(
                    {
                        "code_module": "AAA",
                        "code_presentation": "2014J",
                        "id_student": "42",
                        "id_site": "8",
                        "date": "-2",
                        "sum_click": "2",
                    }
                )
            rows = list(adapt_oulad(source, pseudonym_salt="test-salt"))
            self.assertEqual(len(rows), 2)
            self.assertEqual(rows[0].source, "oulad")
            self.assertNotEqual(rows[0].learner_id, "42")
            self.assertEqual(rows[0].metadata["click_count"], 5)
            self.assertEqual(rows[1].timestamp_ms, -2 * 86_400_000)
            self.assertNotEqual(rows[0].sequence_id, rows[1].sequence_id)
            self.assertEqual((rows[0].sequence_index, rows[1].sequence_index), (0, 0))

    def test_ednet_adapter_is_deterministic_streaming_and_hard_capped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for learner in ("u2", "u1"):
                with (root / f"{learner}.csv").open(
                    "w", encoding="utf-8", newline=""
                ) as handle:
                    writer = csv.DictWriter(
                        handle,
                        fieldnames=(
                            "timestamp",
                            "solving_id",
                            "question_id",
                            "user_answer",
                            "elapsed_time",
                        ),
                    )
                    writer.writeheader()
                    for index in range(4):
                        writer.writerow(
                            {
                                "timestamp": 1_000 + index,
                                "solving_id": index,
                                "question_id": f"q{index}",
                                "user_answer": index % 4,
                                "elapsed_time": 500 + index,
                            }
                        )
            ledger = LicenseLedger(
                (
                    LicenseEntry(
                        source_id="ednet",
                        source_url="https://example.test/ednet",
                        license_name="fixture research terms",
                        permitted_use="research",
                        redistribution="prohibited",
                        derivative_model_restrictions="restricted",
                        retrieval_date="2026-08-02",
                        checksum_sha256=sha256_path(root),
                        research_use_permitted=True,
                        derivative_models_permitted=True,
                    ),
                )
            )
            config = EdNetAdapterConfig(
                root=root,
                event_cap=5,
                pseudonym_salt="test-salt",
                license_ledger=ledger,
            )
            first = list(adapt_ednet(config))
            second = list(adapt_ednet(config))
            self.assertEqual(first, second)
            self.assertEqual(len(first), 5)
            self.assertTrue(all(item.source == "ednet" for item in first))
            with self.assertRaisesRegex(ValueError, "5,000,000"):
                EdNetAdapterConfig(root=root, event_cap=5_000_001)

    def test_license_ledger_preserves_required_fields_and_checksum(self) -> None:
        entry = LicenseEntry(
            source_id="oulad",
            source_url="https://analyse.kmi.open.ac.uk/open_dataset",
            license_name="CC BY 4.0",
            permitted_use="education research",
            redistribution="source license terms apply",
            derivative_model_restrictions="record attribution and verify terms",
            retrieval_date="2026-08-02",
            checksum_sha256="a" * 64,
        )
        ledger = LicenseLedger(entries=(entry,))
        self.assertEqual(ledger.to_dict()["entries"][0]["source_url"], entry.source_url)
        self.assertEqual(ledger.to_dict()["entries"][0]["checksum_sha256"], "a" * 64)
        self.assertEqual(
            TraceDistribution.trace_open().sources, ("oulad", "synthetic")
        )
        self.assertFalse(TraceDistribution.trace_open().license_gated)
        self.assertTrue(TraceDistribution.trace_research().license_gated)
        self.assertFalse(TraceDistribution.trace_research().redistribute_weights)


class SplitAndModelTests(unittest.TestCase):
    def test_learner_disjoint_split_has_no_leakage_and_is_deterministic(self) -> None:
        events = [event(f"learner-{learner}", index) for learner in range(20) for index in range(3)]
        first = learner_disjoint_split(events, seed=9)
        second = learner_disjoint_split(reversed(events), seed=9)
        self.assertEqual(first, second)
        learners = {
            name: {item.learner_id for item in rows}
            for name, rows in first.as_dict().items()
        }
        self.assertFalse(learners["train"] & learners["validation"])
        self.assertFalse(learners["train"] & learners["test"])
        self.assertFalse(learners["validation"] & learners["test"])

    def test_time_forward_split_never_trains_on_future_events(self) -> None:
        events = [event("a", index) for index in range(10)] + [
            event("b", index, timestamp_ms=20_000 + index) for index in range(10)
        ]
        split = time_forward_split(reversed(events), validation_fraction=0.2, test_fraction=0.2)
        for learner in ("a", "b"):
            train = [item for item in split.train if item.learner_id == learner]
            validation = [item for item in split.validation if item.learner_id == learner]
            test = [item for item in split.test if item.learner_id == learner]
            self.assertLess(max(item.timestamp_ms for item in train), min(item.timestamp_ms for item in validation))
            self.assertLess(max(item.timestamp_ms for item in validation), min(item.timestamp_ms for item in test))

    def test_trace_former_teacher_and_student_configs_match_prespecified_scale(self) -> None:
        teacher = TRACEFormerConfig.teacher()
        self.assertEqual(teacher.context_length, 256)
        self.assertGreaterEqual(teacher.estimated_parameters, 20_000_000)
        self.assertLessEqual(teacher.estimated_parameters, 40_000_000)
        self.assertEqual(
            teacher.heads,
            ("retention", "effort", "calibration", "source_risk"),
        )
        self.assertEqual(tuple(config.target_parameters for config in STUDENT_CONFIGS), (3_000_000, 8_000_000, 15_000_000))
        self.assertTrue(all(config.quantization == "int8" for config in STUDENT_CONFIGS))
        self.assertTrue(
            all(
                abs(config.estimated_parameters - config.target_parameters)
                / config.target_parameters
                <= 0.10
                for config in STUDENT_CONFIGS
            )
        )
        self.assertIsInstance(optional_torch_available(), bool)
        if not optional_torch_available():
            with self.assertRaisesRegex(RuntimeError, "adapt-model"):
                build_trace_former(teacher)

    def test_baseline_registry_and_bkt_are_complete_and_deterministic(self) -> None:
        self.assertEqual(
            BASELINE_NAMES,
            ("logistic_regression", "bkt", "gru", "ordinary_transformer"),
        )
        model = BktBaseline(learn_probability=0.2, guess_probability=0.2, slip_probability=0.1)
        predictions = [model.observe(value) for value in (True, True, False)]
        self.assertEqual(predictions, BktBaseline(0.2, 0.2, 0.1).predict_sequence((True, True, False)))


class EvaluationGateAndManifestTests(unittest.TestCase):
    def setUp(self) -> None:
        self.teacher = [
            Prediction(f"e{i}", f"l{i}", bool(i % 2), 0.85 if i % 2 else 0.15)
            for i in range(40)
        ]
        self.baseline = [
            Prediction(f"e{i}", f"l{i}", bool(i % 2), 0.70 if i % 2 else 0.30)
            for i in range(40)
        ]

    def test_evaluation_calibration_and_bootstrap_are_deterministic(self) -> None:
        metrics = evaluate_predictions(self.teacher)
        self.assertLess(metrics.brier, evaluate_predictions(self.baseline).brier)
        self.assertGreater(metrics.auroc, 0.99)
        calibrated = TemperatureCalibrator(temperature=2.0).transform(self.teacher)
        self.assertTrue(all(0.0 < item.probability < 1.0 for item in calibrated))
        first = bootstrap_relative_brier_improvement(self.teacher, self.baseline, iterations=100, seed=3)
        second = bootstrap_relative_brier_improvement(self.teacher, self.baseline, iterations=100, seed=3)
        self.assertEqual(first, second)
        self.assertGreater(first.lower_95, 0.0)

    def test_gate_order_is_fixed_and_unmeasured_gates_fail_closed(self) -> None:
        evidence = CandidateEvidence(candidate_id="student-8m", benchmark_kind="synthetic")
        result = evaluate_gates(evidence)
        self.assertEqual(tuple(item.gate_id for item in result.gates), GATE_ORDER)
        self.assertFalse(result.all_gates_passed)
        self.assertTrue(any(item.status == "unmeasured" for item in result.gates))

    def test_real_measured_candidate_can_pass_without_hard_coded_size_preference(self) -> None:
        common = {
            "benchmark_kind": "real",
            "strongest_baseline": "ordinary_transformer",
            "measurement_provenance_sha256": "b" * 64,
            "artifact_sha256": "a" * 64,
            "relative_brier_improvement": 0.08,
            "relative_brier_ci_low": 0.01,
            "ece": 0.04,
            "distilled_brier_gap": 0.009,
            "distilled_auroc_gap": 0.019,
            "incremental_ram_bytes": 200 * 1024 * 1024,
            "p95_inference_ms": 140.0,
            "reference_device": "named-device / 8GB RAM / 6-core CPU",
        }
        candidate = CandidateEvidence(candidate_id="student-15m", artifact_size_bytes=24 * 1024 * 1024, **common)
        result = evaluate_gates(candidate)
        self.assertTrue(result.all_gates_passed)
        self.assertTrue(all(item.status == "passed" for item in result.gates))

    def test_benchmark_computes_strongest_baseline_and_provenance_bound_evidence(self) -> None:
        candidate = [
            Prediction(f"e{i}", f"l{i}", bool(i % 2), 0.96 if i % 2 else 0.04)
            for i in range(40)
        ]
        teacher = [
            Prediction(f"e{i}", f"l{i}", bool(i % 2), 0.97 if i % 2 else 0.03)
            for i in range(40)
        ]
        baselines = {
            name: [
                Prediction(
                    f"e{i}",
                    f"l{i}",
                    bool(i % 2),
                    probability if i % 2 else 1 - probability,
                )
                for i in range(40)
            ]
            for name, probability in {
                "logistic_regression": 0.70,
                "bkt": 0.65,
                "gru": 0.68,
                "ordinary_transformer": 0.72,
            }.items()
        }
        outcome = benchmark_candidate(
            candidate,
            teacher,
            baselines,
            benchmark_kind="real",
            candidate_id="student-measured",
            resource=ResourceEvidence(
                artifact_size_bytes=4_000_000,
                artifact_sha256="a" * 64,
                incremental_ram_bytes=100_000_000,
                p95_inference_ms=80.0,
                reference_device="Forge-PC / 8GB RAM / 6-core CPU",
            ),
            input_hashes={"candidate_predictions": "c" * 64},
            bootstrap_iterations=100,
            seed=8,
        )
        self.assertEqual(outcome.evidence.strongest_baseline, "ordinary_transformer")
        self.assertEqual(len(outcome.evidence.measurement_provenance_sha256 or ""), 64)
        self.assertTrue(evaluate_gates(outcome.evidence).all_gates_passed)
        self.assertTrue(all(item.status == "measured" for item in outcome.baselines))

    def test_synthetic_manifest_never_claims_approval_and_is_atomic_and_hashed(self) -> None:
        baselines = [BaselineResult(name=name, brier=None, auroc=None, status="unmeasured") for name in BASELINE_NAMES]
        gates = evaluate_gates(CandidateEvidence(candidate_id="student-3m", benchmark_kind="synthetic"))
        manifest = build_manifest(
            model_id="trace-fixture",
            dataset_kind="synthetic",
            candidate_id="student-3m",
            gates=gates,
            baselines=baselines,
        )
        self.assertEqual(manifest["approval_status"], "not_approved")
        self.assertEqual(manifest["claim_scope"], "software_validation_only")
        self.assertFalse(manifest["online_weight_update"])
        self.assertEqual(tuple(manifest["controller_actions"]), CONTROL_ACTIONS)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "nested" / "manifest.json"
            digest = write_manifest(output, manifest)
            self.assertEqual(digest, hashlib.sha256(output.read_bytes()).hexdigest())
            self.assertEqual(list(output.parent.glob("*.partial")), [])

    def test_controller_falls_back_to_fixed_order_on_ood_or_uncertainty(self) -> None:
        policy = ControllerPolicy(max_ood_score=0.5, max_uncertainty=0.4)
        decision = policy.decide(UncertaintySignal(ood_score=0.7, uncertainty=0.2))
        self.assertEqual(decision.delivery_mode, "fixed_order")
        self.assertEqual(decision.action, "pause")


class CliTests(unittest.TestCase):
    def test_generate_synthetic_and_manifest_cli_write_atomic_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            events = root / "events.jsonl"
            self.assertEqual(
                main(
                    [
                        "generate-synthetic",
                        "--output",
                        str(events),
                        "--seed",
                        "5",
                        "--learners",
                        "2",
                        "--events-per-learner",
                        "4",
                    ]
                ),
                0,
            )
            self.assertEqual(len(events.read_text(encoding="utf-8").splitlines()), 8)
            evidence = root / "evidence.json"
            evidence.write_text(
                json.dumps({"candidate_id": "student-3m", "benchmark_kind": "synthetic"}),
                encoding="utf-8",
            )
            manifest = root / "manifest.json"
            self.assertEqual(
                main(
                    [
                        "produce-manifest",
                        "--evidence",
                        str(evidence),
                        "--output",
                        str(manifest),
                        "--model-id",
                        "fixture",
                    ]
                ),
                0,
            )
            payload = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(payload["approval_status"], "not_approved")


if __name__ == "__main__":
    unittest.main()
