from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch
from pathlab_ai_model.core import (
    LABELS,
    choose_review_threshold,
    group_view_logits,
    metrics_from_probabilities,
    sha256,
)
from pathlab_ai_model.inference import PathLabPredictor
from PIL import Image


class PathLabAiModelTests(unittest.TestCase):
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
        self.assertGreaterEqual(review["threshold"], 0.0)
        self.assertLessEqual(review["threshold"], 1.0)

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


if __name__ == "__main__":
    unittest.main()
