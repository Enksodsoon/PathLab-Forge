from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch
from pathlab_ai_model.core import (
    COARSE_GROUP,
    LABELS,
    choose_review_threshold,
    group_view_logits,
    metrics_from_probabilities,
    sha256,
    write_json_atomic,
)
from pathlab_ai_model.inference import (
    PathLabMilPredictor,
    PathLabPredictor,
    _verify_mil_selection_provenance,
    build_suspected_regions,
)
from pathlab_ai_model.mil import (
    GatedAttentionMil,
    MilTestEvaluationConfig,
    MilTrainingConfig,
    _acquire_locked_test_attempt,
    _process_memory_usage,
    evaluate_bracs_mil_test,
    patient_sample_weights,
    train_bracs_mil,
)
from pathlab_ai_model.wsi_bags import (
    ENCODER_HUBCONF_SHA256,
    ENCODER_RELEASE,
    ENCODER_WEIGHTS_SHA256,
    build_bracs_development_inventory,
    build_bracs_final_inventory,
    parse_ftp_listing,
    select_tissue_tiles,
    shard_bracs_wsi_inventory,
    verify_bracs_wsi_bags,
)
from PIL import Image


class PathLabAiModelTests(unittest.TestCase):
    def test_native_process_memory_telemetry_is_positive(self) -> None:
        usage = _process_memory_usage()
        peak = usage["peak_working_set_bytes"]
        self.assertIsNotNone(peak)
        self.assertGreater(int(peak), 0)

    def test_atomic_json_write_replaces_complete_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "result.json"
            write_json_atomic(path, {"version": 1, "values": [1, 2]})
            write_json_atomic(path, {"version": 2, "status": "complete"})
            self.assertEqual(
                json.loads(path.read_text(encoding="utf-8")),
                {"version": 2, "status": "complete"},
            )
            self.assertEqual(list(path.parent.glob("*.tmp")), [])

    def test_locked_test_attempt_lock_is_atomic_and_durable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock = _acquire_locked_test_attempt(root)
            record = json.loads(lock.read_text(encoding="utf-8"))
            self.assertEqual(record["status"], "locked_test_evaluation_started")
            with self.assertRaisesRegex(RuntimeError, "already started"):
                _acquire_locked_test_attempt(root)

    def test_full_development_inventory_excludes_locked_test(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "full.jsonl"
            output = root / "development" / "inventory.jsonl"
            rows = []
            for split, count in (("train", 392), ("validation", 68)):
                for index in range(count):
                    label = LABELS[index % len(LABELS)]
                    rows.append(
                        {
                            "slide_id": f"BRACS_{len(rows) + 1}",
                            "patient_id": f"{split}-{index}",
                            "label": label,
                            "coarse_group": COARSE_GROUP[label],
                            "split": split,
                            "source_size_bytes": index + 1,
                        }
                    )
            rows.append(
                {
                    "slide_id": "BRACS_9999",
                    "patient_id": "test-1",
                    "label": "N",
                    "coarse_group": COARSE_GROUP["N"],
                    "split": "test",
                    "source_size_bytes": 1,
                }
            )
            source.write_text(
                "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8"
            )

            report = build_bracs_development_inventory(source, output)
            written = [
                json.loads(line)
                for line in output.read_text(encoding="utf-8").splitlines()
            ]

            self.assertEqual(report["slides"], 460)
            self.assertEqual(report["split_counts"], {"train": 392, "validation": 68})
            self.assertNotIn("test", {row["split"] for row in written})
            self.assertEqual(len({row["patient_id"] for row in written}), 460)

    def test_inventory_shards_are_deterministic_disjoint_and_byte_balanced(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = root / "inventory.jsonl"
            rows = [
                {
                    "slide_id": f"BRACS_{index + 1}",
                    "patient_id": str(index + 1),
                    "label": "N",
                    "split": "train",
                    "source_size_bytes": size,
                }
                for index, size in enumerate((10, 9, 8, 7, 6, 5, 4, 3, 2))
            ]
            inventory.write_text(
                "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8"
            )
            first = shard_bracs_wsi_inventory(inventory, root / "first", shards=3)
            second = shard_bracs_wsi_inventory(inventory, root / "second", shards=3)

            identities = []
            for index in range(3):
                first_rows = [
                    json.loads(line)
                    for line in (root / "first" / f"shard-{index}.jsonl")
                    .read_text(encoding="utf-8")
                    .splitlines()
                ]
                second_rows = [
                    json.loads(line)
                    for line in (root / "second" / f"shard-{index}.jsonl")
                    .read_text(encoding="utf-8")
                    .splitlines()
                ]
                self.assertEqual(first_rows, second_rows)
                identities.extend(row["slide_id"] for row in first_rows)
            totals = [row["source_bytes"] for row in first["shards"]]
            self.assertEqual(
                sorted(identities), sorted(row["slide_id"] for row in rows)
            )
            self.assertEqual(len(identities), len(set(identities)))
            self.assertLessEqual(max(totals) - min(totals), 2)
            self.assertEqual(first["source_bytes"], second["source_bytes"])

    def test_parses_official_ftp_listing(self) -> None:
        listing = (
            "-r--r--r-- 1 ftp ftp 654452163 Jan 12 2022 BRACS_1003675.svs\n"
            "drwxr-xr-x 1 ftp ftp 0 Jan 12 2022 ignored\n"
        )
        self.assertEqual(parse_ftp_listing(listing), {"BRACS_1003675.svs": 654_452_163})

    def test_unannotated_tissue_sampler_is_bounded_and_deterministic(self) -> None:
        class FakeSlide:
            dimensions = (2_048, 1_024)

            @staticmethod
            def get_thumbnail(size: tuple[int, int]) -> Image.Image:
                del size
                pixels = np.zeros((256, 512, 3), dtype=np.uint8)
                pixels[:, :] = (180, 80, 140)
                return Image.fromarray(pixels)

        first = select_tissue_tiles(FakeSlide(), 224, 12)
        second = select_tissue_tiles(FakeSlide(), 224, 12)
        self.assertEqual(first, second)
        self.assertEqual(len(first[0]), 12)
        self.assertTrue(all(0.0 <= value <= 1.0 for value in first[1]))

    def test_gated_attention_mil_returns_normalized_tile_evidence(self) -> None:
        model = GatedAttentionMil(input_dim=8, hidden_dim=4, classes=7).eval()
        instances = torch.ones((11, 8))
        logits, attention = model(instances)
        contributions, evidence_attention = model.class_evidence(instances)
        self.assertEqual(tuple(logits.shape), (7,))
        self.assertEqual(tuple(attention.shape), (11,))
        self.assertAlmostEqual(float(attention.sum().detach()), 1.0, places=6)
        torch.testing.assert_close(attention, evidence_attention)
        torch.testing.assert_close(
            logits,
            contributions.sum(dim=0) + model.classifier.bias,
        )

    def test_patient_weights_equalize_total_multi_slide_contribution(self) -> None:
        bags = [
            {"patient_id": "single"},
            {"patient_id": "multiple"},
            {"patient_id": "multiple"},
        ]
        weights = patient_sample_weights(bags)
        self.assertAlmostEqual(float(weights.mean()), 1.0)
        self.assertAlmostEqual(float(weights[0]), float(weights[1] + weights[2]))

    def test_groups_two_views_without_crossing_roi_identity(self) -> None:
        logits, labels, patients, roi_ids = group_view_logits(
            np.asarray([[1.0, 0.0], [3.0, 2.0], [0.0, 4.0], [2.0, 6.0]]),
            np.asarray(["roi-a", "roi-a", "roi-b", "roi-b"]),
            np.asarray([0, 0, 1, 1]),
            np.asarray(["p1", "p1", "p2", "p2"]),
        )
        np.testing.assert_allclose(logits, [[2.0, 1.0], [1.0, 5.0]])
        np.testing.assert_array_equal(labels, [0, 1])
        np.testing.assert_array_equal(patients, ["p1", "p2"])
        np.testing.assert_array_equal(roi_ids, ["roi-a", "roi-b"])

    def test_metrics_and_review_threshold_are_bounded(self) -> None:
        truth = np.arange(len(LABELS))
        probabilities = np.eye(len(LABELS)) * 0.9 + 0.1 / len(LABELS)
        probabilities /= probabilities.sum(axis=1, keepdims=True)
        metrics = metrics_from_probabilities(truth, probabilities)
        review = choose_review_threshold(truth, probabilities)
        self.assertEqual(metrics["accuracy"], 1.0)
        self.assertGreaterEqual(metrics["multiclass_brier_score"], 0.0)
        self.assertGreaterEqual(metrics["expected_calibration_error_10_bin"], 0.0)
        self.assertLessEqual(metrics["expected_calibration_error_10_bin"], 1.0)
        self.assertGreaterEqual(review["threshold"], 0.0)
        self.assertLessEqual(review["threshold"], 1.0)
        self.assertEqual(review["target_accuracy"], 0.65)
        self.assertEqual(review["minimum_coverage"], 0.10)
        self.assertIn(
            review["selection_status"],
            {"target_met", "fallback_top_confidence_decile"},
        )

    def test_macro_f1_keeps_all_classes_when_a_resample_omits_classes(self) -> None:
        probabilities = np.zeros((1, len(LABELS)), dtype=np.float64)
        probabilities[0, 0] = 1.0
        metrics = metrics_from_probabilities(np.asarray([0]), probabilities)
        self.assertAlmostEqual(metrics["macro_f1"], 1.0 / len(LABELS))
        self.assertAlmostEqual(metrics["coarse_macro_f1"], 1.0 / 3.0)

    def test_predictor_loads_checksum_verified_torchscript(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = torch.nn.Sequential(
                torch.nn.Flatten(), torch.nn.Linear(3 * 224 * 224, 7)
            )
            with torch.no_grad():
                model[1].weight.zero_()
                model[1].bias.copy_(torch.arange(7, dtype=torch.float32))
            artifact = root / "model.ts"
            torch.jit.save(
                torch.jit.trace(model, torch.zeros((1, 3, 224, 224))), artifact
            )
            (root / "model_config.json").write_text(
                json.dumps(
                    {
                        "model_name": "fixture",
                        "labels": list(LABELS),
                        "coarse_groups": {},
                        "temperature": 1.0,
                        "review_threshold": 0.0,
                        "torchscript": artifact.name,
                        "torchscript_sha256": sha256(artifact),
                        "intended_use": "test",
                    }
                ),
                encoding="utf-8",
            )
            result = PathLabPredictor(root).predict_image(
                Image.new("RGB", (300, 200), "white")
            )
            self.assertEqual(result["label"], "IC")
            self.assertFalse(result["needs_review"])
            self.assertAlmostEqual(sum(result["probabilities"].values()), 1.0, places=6)

    def test_mil_predictor_returns_ranked_spatial_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "mil.ts"
            torch.jit.save(
                torch.jit.script(GatedAttentionMil(8, 4, len(LABELS)).eval()),
                artifact,
            )
            (root / "model_config.json").write_text(
                json.dumps(
                    {
                        "model_name": "mil-fixture",
                        "encoder": "kaiko-vits16",
                        "input_dimension": 8,
                        "temperature": 1.0,
                        "review_threshold": 0.5,
                        "torchscript": artifact.name,
                        "torchscript_sha256": sha256(artifact),
                        "intended_use": "test",
                    }
                ),
                encoding="utf-8",
            )
            result = PathLabMilPredictor(root).predict_features(
                np.ones((3, 8), dtype=np.float32),
                np.asarray([[0, 0], [224, 0], [448, 0]]),
                np.asarray([0.8, 0.9, 0.7]),
            )
            self.assertEqual(result["tile_count"], 3)
            self.assertAlmostEqual(sum(result["probabilities"].values()), 1.0, places=6)
            self.assertEqual(result["review"]["required"], result["needs_review"])
            self.assertIn(
                result["review"]["reason"],
                {
                    "confidence_below_validation_threshold",
                    "validation_threshold_passed",
                },
            )
            attention = [row["attention"] for row in result["tile_evidence"]]
            self.assertAlmostEqual(sum(attention), 1.0, places=6)
            strengths = [
                row["predicted_class_evidence_strength"]
                for row in result["tile_evidence"]
            ]
            self.assertEqual(strengths, sorted(strengths, reverse=True))
            self.assertAlmostEqual(sum(strengths), 1.0, places=6)
            self.assertEqual(
                [row["rank"] for row in result["tile_evidence"]], [1, 2, 3]
            )
            with self.assertRaisesRegex(ValueError, "feature dimension mismatch"):
                PathLabMilPredictor(root).predict_features(
                    np.ones((3, 7), dtype=np.float32),
                    np.asarray([[0, 0], [224, 0], [448, 0]]),
                    np.asarray([0.8, 0.9, 0.7]),
                )
            with self.assertRaisesRegex(ValueError, "one value per tile"):
                PathLabMilPredictor(root).predict_features(
                    np.ones((3, 8), dtype=np.float32),
                    np.asarray([[0, 0], [224, 0], [448, 0]]),
                    np.asarray([0.8, 0.9]),
                )

    def test_mil_evidence_clusters_into_one_auto_selected_leading_region(self) -> None:
        evidence = [
            {"x": 0, "y": 0, "attention": 0.3, "predicted_class_contribution": 5.0},
            {"x": 224, "y": 0, "attention": 0.2, "predicted_class_contribution": 4.0},
            {"x": 4480, "y": 4480, "attention": 0.1, "predicted_class_contribution": 3.0},
            {"x": 4704, "y": 4480, "attention": 0.1, "predicted_class_contribution": 2.0},
            {"x": 9000, "y": 9000, "attention": 0.3, "predicted_class_contribution": -9.0},
        ]
        regions = build_suspected_regions(evidence, 224)
        self.assertEqual(len(regions), 2)
        self.assertEqual(regions[0]["id"], "evidence-1")
        self.assertTrue(regions[0]["auto_selected"])
        self.assertFalse(regions[1]["auto_selected"])
        self.assertEqual(regions[0]["tile_count"], 2)
        self.assertEqual(regions[0]["width"], 448)
        self.assertEqual(sum(bool(row["auto_selected"]) for row in regions), 1)
        self.assertEqual(build_suspected_regions([], 224), [])

    def test_mil_selection_provenance_detects_baseline_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output_root = Path(directory)
            model_root = output_root / "candidate"
            model_root.mkdir()
            artifact = model_root / "mil.ts"
            evaluation = model_root / "evaluation.json"
            baseline = model_root / "mean_pool_baselines.joblib"
            artifact.write_bytes(b"model")
            evaluation.write_text("{}", encoding="utf-8")
            baseline.write_bytes(b"baseline")
            config = {
                "mean_pool_baselines": baseline.name,
                "mean_pool_baselines_sha256": sha256(baseline),
            }
            config_path = model_root / "model_config.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            gates = {"example_gate": True}
            hashes = {
                "artifact_sha256": sha256(artifact),
                "model_config_sha256": sha256(config_path),
                "evaluation_sha256": sha256(evaluation),
                "mean_pool_baselines_sha256": sha256(baseline),
            }
            grid_path = output_root / "validation_grid.json"
            grid_path.write_text(
                json.dumps(
                    {
                        "selected": "candidate",
                        "advancement_gates": gates,
                        "advance_to_locked_test": True,
                        "candidates": [{"name": "candidate", **hashes}],
                    }
                ),
                encoding="utf-8",
            )
            selection_path = output_root / "selected_model.json"
            selection_path.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "candidate": "candidate",
                        "candidate_path": str(model_root.resolve()),
                        **hashes,
                        "validation_grid_sha256": sha256(grid_path),
                        "advancement_gates": gates,
                        "advance_to_locked_test": True,
                    }
                ),
                encoding="utf-8",
            )

            provenance = _verify_mil_selection_provenance(
                model_root, config, sha256(artifact)
            )
            self.assertIsNotNone(provenance)
            assert provenance is not None
            self.assertTrue(provenance["integrity_verified"])
            self.assertTrue(provenance["advance_to_locked_test"])

            baseline.write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "baseline artifact checksum"):
                _verify_mil_selection_provenance(model_root, config, sha256(artifact))

            baseline.write_bytes(b"baseline")
            grid_path.write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "validation-grid checksum"):
                _verify_mil_selection_provenance(model_root, config, sha256(artifact))

    def test_validation_only_mil_does_not_require_or_emit_test_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bags_root = root / "bags"
            records = []
            for split, repetitions in (("train", 2), ("validation", 1)):
                for class_index, label in enumerate(LABELS):
                    for repetition in range(repetitions):
                        slide = f"{split}-{label}-{repetition}"
                        path = Path("kaiko-vits16") / split / label / f"{slide}.npz"
                        (bags_root / path).parent.mkdir(parents=True, exist_ok=True)
                        features = np.full(
                            (3, 8), class_index + repetition / 10, dtype=np.float16
                        )
                        np.savez_compressed(bags_root / path, features=features)
                        records.append(
                            {
                                "slide_id": slide,
                                "patient_id": slide,
                                "label": label,
                                "split": split,
                                "encoder": "kaiko-vits16",
                                "bag_path": path.as_posix(),
                            }
                        )
            (bags_root / "bags.jsonl").write_text(
                "".join(json.dumps(record) + "\n" for record in records),
                encoding="utf-8",
            )
            output = root / "model"
            result = train_bracs_mil(
                MilTrainingConfig(
                    bags_root=bags_root,
                    output_root=output,
                    hidden_dim=4,
                    max_epochs=1,
                    patience=1,
                    bootstrap_iterations=10,
                    validation_only=True,
                )
            )
            self.assertEqual(result["selection_stage"], "validation_only")
            self.assertTrue(result["artifact_verification"]["finite"])
            self.assertIn(
                "validation_mil_minus_mean_pool",
                result["paired_comparison"],
            )
            self.assertIn("majority_class", result["baselines"])
            self.assertIn("validation_mil_minus_majority", result["paired_comparison"])
            self.assertLessEqual(
                result["artifact_verification"][
                    "evidence_reconstruction_max_absolute_error"
                ],
                1e-5,
            )
            self.assertIsNone(result["test"])
            self.assertFalse((output / "test_predictions.csv").exists())
            model_config = json.loads(
                (output / "model_config.json").read_text(encoding="utf-8")
            )
            self.assertAlmostEqual(
                sum(model_config["training_class_probabilities"].values()), 1.0
            )
            self.assertEqual(
                model_config["mean_pool_baselines_sha256"],
                sha256(output / model_config["mean_pool_baselines"]),
            )
            repeated = train_bracs_mil(
                MilTrainingConfig(
                    bags_root=bags_root,
                    output_root=root / "model-repeat",
                    hidden_dim=4,
                    max_epochs=1,
                    patience=1,
                    bootstrap_iterations=10,
                    validation_only=True,
                )
            )
            self.assertEqual(result["training_history"], repeated["training_history"])
            self.assertEqual(
                result["validation"],
                repeated["validation"],
            )
            self.assertEqual(
                result["artifacts"]["torchscript_sha256"],
                repeated["artifacts"]["torchscript_sha256"],
            )
            for class_index, label in enumerate(LABELS):
                slide = f"test-{label}"
                path = Path("kaiko-vits16") / "test" / label / f"{slide}.npz"
                (bags_root / path).parent.mkdir(parents=True, exist_ok=True)
                np.savez_compressed(
                    bags_root / path,
                    features=np.full((3, 8), class_index, dtype=np.float16),
                )
                records.append(
                    {
                        "slide_id": slide,
                        "patient_id": slide,
                        "label": label,
                        "split": "test",
                        "encoder": "kaiko-vits16",
                        "bag_path": path.as_posix(),
                    }
                )
            (bags_root / "bags.jsonl").write_text(
                "".join(json.dumps(record) + "\n" for record in records),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "slide count mismatch"):
                evaluate_bracs_mil_test(
                    MilTestEvaluationConfig(
                        bags_root=bags_root,
                        model_root=output,
                        bootstrap_iterations=10,
                        expected_test_slides=len(LABELS) + 1,
                    )
                )
            with self.assertRaisesRegex(ValueError, "selection record is required"):
                evaluate_bracs_mil_test(
                    MilTestEvaluationConfig(
                        bags_root=bags_root,
                        model_root=output,
                        bootstrap_iterations=10,
                        require_selection_record=True,
                    )
                )
            legacy_selection = output.parent / "selected_model.json"
            legacy_selection.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "advance_to_locked_test": True,
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "requires a v2"):
                evaluate_bracs_mil_test(
                    MilTestEvaluationConfig(
                        bags_root=bags_root,
                        model_root=output,
                        bootstrap_iterations=10,
                        require_selection_record=True,
                    )
                )
            legacy_selection.unlink()
            baseline_path = output / model_config["mean_pool_baselines"]
            original_baseline = baseline_path.read_bytes()
            baseline_path.write_bytes(original_baseline + b"tampered")
            with self.assertRaisesRegex(ValueError, "baseline checksum mismatch"):
                evaluate_bracs_mil_test(
                    MilTestEvaluationConfig(
                        bags_root=bags_root,
                        model_root=output,
                        bootstrap_iterations=10,
                    )
                )
            baseline_path.write_bytes(original_baseline)
            final = evaluate_bracs_mil_test(
                MilTestEvaluationConfig(
                    bags_root=bags_root,
                    model_root=output,
                    bootstrap_iterations=10,
                )
            )
            self.assertEqual(final["evaluation_stage"], "locked_test_once")
            self.assertEqual(final["slides"], len(LABELS))
            self.assertIn("majority_class_baseline", final)
            self.assertIn("mil_minus_majority", final)
            with self.assertRaisesRegex(FileExistsError, "already exists"):
                evaluate_bracs_mil_test(
                    MilTestEvaluationConfig(bags_root=bags_root, model_root=output)
                )

    def test_wsi_bag_verifier_requires_exact_integral_cohort(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bag_path = root / "kaiko-vits16/train/N/BRACS_1.npz"
            bag_path.parent.mkdir(parents=True)
            np.savez_compressed(
                bag_path,
                features=np.ones((8, 384), dtype=np.float16),
                coordinates=np.asarray(
                    [[index * 224, 0] for index in range(8)], dtype=np.int32
                ),
                tissue_scores=np.full(8, 0.5, dtype=np.float32),
            )
            inventory = root / "inventory.jsonl"
            inventory.write_text(
                json.dumps(
                    {
                        "slide_id": "BRACS_1",
                        "patient_id": "1",
                        "label": "N",
                        "split": "train",
                        "source_size_bytes": 123,
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            record = {
                "slide_id": "BRACS_1",
                "patient_id": "1",
                "label": "N",
                "split": "train",
                "source_size_bytes": 123,
                "tile_count": 8,
                "feature_dimension": 384,
                "slide_width": 2048,
                "slide_height": 1024,
                "source_tile_pixels": 224,
                "target_mpp": 0.5,
                "encoder": "kaiko-vits16",
                "encoder_release": ENCODER_RELEASE,
                "encoder_hubconf_sha256": ENCODER_HUBCONF_SHA256,
                "encoder_weights_sha256": ENCODER_WEIGHTS_SHA256,
                "bag_path": bag_path.relative_to(root).as_posix(),
                "bag_sha256": sha256(bag_path),
            }
            (root / "bags.jsonl").write_text(
                json.dumps(record) + "\n", encoding="utf-8"
            )
            report = verify_bracs_wsi_bags(inventory, root)
            self.assertEqual(report["status"], "verified")
            self.assertEqual(report["bags"], 1)
            self.assertEqual(report["source_bytes"], 123)
            self.assertEqual(report["total_tile_vectors"], 8)
            self.assertGreater(report["feature_bag_bytes"], 0)
            bag_path.write_bytes(b"corrupted")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                verify_bracs_wsi_bags(inventory, root)

    def test_final_inventory_keeps_frozen_development_and_every_test_slide(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            development_row = {
                "slide_id": "BRACS_1",
                "patient_id": "development-patient",
                "label": "N",
                "split": "train",
                "source_size_bytes": 10,
            }
            test_rows = [
                {
                    "slide_id": f"BRACS_{index + 100}",
                    "patient_id": f"test-patient-{index}",
                    "label": LABELS[index % len(LABELS)],
                    "split": "test",
                    "source_size_bytes": 20,
                }
                for index in range(87)
            ]
            development = root / "development.jsonl"
            full = root / "full.jsonl"
            output = root / "final.jsonl"
            development.write_text(json.dumps(development_row) + "\n", encoding="utf-8")
            full.write_text(
                "".join(
                    json.dumps(row) + "\n" for row in [development_row, *test_rows]
                ),
                encoding="utf-8",
            )
            report = build_bracs_final_inventory(development, full, output)
            self.assertEqual(report["split_counts"], {"train": 1, "test": 87})
            self.assertEqual(len(output.read_text(encoding="utf-8").splitlines()), 88)


if __name__ == "__main__":
    unittest.main()
