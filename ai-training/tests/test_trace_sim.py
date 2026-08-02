from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

from pathlab_adapt.artifact_store import verify_dataset_manifest, write_parquet_dataset
from pathlab_adapt.features import FEATURE_NAMES, encode_features, encode_targets, encode_windows
from pathlab_adapt.models import STUDENT_CONFIGS, TRACEFormerConfig
from pathlab_adapt.ontology import TRACE_SIM_HEADS
from pathlab_adapt.synthetic_suite import (
    SyntheticSuiteConfig,
    coverage_report,
    generate_synthetic_suite,
    scenario_catalog,
)


class TraceSimTests(unittest.TestCase):
    def _events(self, count: int = 16_384):
        return list(generate_synthetic_suite(SyntheticSuiteConfig(event_count=count)))

    def test_simulator_is_exact_deterministic_and_covers_scenarios(self) -> None:
        first = self._events()
        second = self._events()
        self.assertEqual(len(first), 16_384)
        self.assertEqual([item.to_dict() for item in first], [item.to_dict() for item in second])
        report = coverage_report(first)
        self.assertEqual(report["scenario_families"], len(scenario_catalog()))
        self.assertTrue(report["coverage_complete"])

    def test_counterfactual_pairs_change_the_named_signal(self) -> None:
        events = self._events()
        pairs: dict[str, list] = {}
        for event in events:
            pair_id = event.metadata["counterfactual_pair_id"]
            if pair_id:
                pairs.setdefault(pair_id, []).append(event)
        complete = [rows for rows in pairs.values() if len(rows) == 2]
        self.assertGreater(len(complete), 100)
        for left, right in complete[:100]:
            varied = left.metadata["counterfactual_varied_feature"]
            if varied == "confidence":
                self.assertNotEqual(left.confidence, right.confidence)
            elif varied == "hint_used":
                self.assertNotEqual(left.hint_used, right.hint_used)
            else:
                self.assertNotEqual(left.effort, right.effort)

    def test_feature_schema_and_all_five_targets_are_present(self) -> None:
        events = self._events(12_288)
        self.assertEqual(len(encode_features(events[0])), len(FEATURE_NAMES))
        self.assertEqual(tuple(encode_targets(events[0])), TRACE_SIM_HEADS)
        windows = encode_windows(events, vocabulary=20_000, context=32, stride=8)
        self.assertTrue(windows)
        self.assertLessEqual(max(len(window.tokens) for window in windows), 32)

    def test_model_configs_have_five_heads(self) -> None:
        self.assertEqual(TRACEFormerConfig.teacher().heads, TRACE_SIM_HEADS)
        self.assertTrue(all(config.heads == TRACE_SIM_HEADS for config in STUDENT_CONFIGS))

    @unittest.skipUnless(importlib.util.find_spec("pyarrow"), "pyarrow is optional")
    def test_content_addressed_parquet_store_verifies_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = write_parquet_dataset(self._events(12_288), root=Path(directory), shard_events=4_096)
            path = Path(str(manifest["path"])) / "manifest.json"
            verified = verify_dataset_manifest(path)
            self.assertEqual(verified["event_count"], 12_288)
            self.assertTrue(verified["immutable"])


if __name__ == "__main__":
    unittest.main()
