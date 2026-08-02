"""Run the fixed BRACS MIL hyperparameter grid without touching test bags."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from pathlab_ai_model.core import sha256, write_json_atomic
from pathlab_ai_model.mil import MilTrainingConfig, train_bracs_mil

CANDIDATES = (
    {
        "name": "h64-lr1e3-wd1e4",
        "hidden_dim": 64,
        "learning_rate": 1e-3,
        "weight_decay": 1e-4,
    },
    {
        "name": "h128-lr1e3-wd1e4",
        "hidden_dim": 128,
        "learning_rate": 1e-3,
        "weight_decay": 1e-4,
    },
    {
        "name": "h256-lr1e3-wd1e4",
        "hidden_dim": 256,
        "learning_rate": 1e-3,
        "weight_decay": 1e-4,
    },
    {
        "name": "h128-lr3e4-wd1e4",
        "hidden_dim": 128,
        "learning_rate": 3e-4,
        "weight_decay": 1e-4,
    },
    {
        "name": "h128-lr1e3-wd1e3",
        "hidden_dim": 128,
        "learning_rate": 1e-3,
        "weight_decay": 1e-3,
    },
)


def _all_numeric_values_finite(value: object) -> bool:
    if isinstance(value, dict):
        return all(_all_numeric_values_finite(item) for item in value.values())
    if isinstance(value, (list, tuple)):
        return all(_all_numeric_values_finite(item) for item in value)
    if isinstance(value, (int, float)):
        return math.isfinite(value)
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bags-root", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--max-epochs", type=int, default=200)
    parser.add_argument("--patience", type=int, default=25)
    parser.add_argument("--seed", type=int, default=20260802)
    args = parser.parse_args()
    args.output_root.mkdir(parents=True, exist_ok=True)
    rows = []
    for candidate in CANDIDATES:
        result = train_bracs_mil(
            MilTrainingConfig(
                bags_root=args.bags_root,
                output_root=args.output_root / candidate["name"],
                hidden_dim=candidate["hidden_dim"],
                learning_rate=candidate["learning_rate"],
                weight_decay=candidate["weight_decay"],
                max_epochs=args.max_epochs,
                patience=args.patience,
                seed=args.seed,
                validation_only=True,
            )
        )
        rows.append(
            {
                **candidate,
                "best_epoch": result["best_epoch"],
                "validation": result["validation"],
                "review_policy": result["review_threshold"],
                "mean_pool_validation": result["baselines"]["mean_pool_logistic"][
                    "validation"
                ],
                "majority_class_validation": result["baselines"]["majority_class"][
                    "validation"
                ],
                "artifact_sha256": result["artifacts"]["torchscript_sha256"],
                "model_config_sha256": sha256(
                    args.output_root / candidate["name"] / "model_config.json"
                ),
                "evaluation_sha256": sha256(
                    args.output_root / candidate["name"] / "evaluation.json"
                ),
                "mean_pool_baselines_sha256": result["artifacts"][
                    "mean_pool_baselines_sha256"
                ],
            }
        )
    rows.sort(
        key=lambda row: (
            row["validation"]["macro_f1"],
            row["validation"]["coarse_accuracy"],
            -row["validation"]["expected_calibration_error_10_bin"],
            -row["hidden_dim"],
        ),
        reverse=True,
    )
    selected = rows[0]
    selected_metrics = selected["validation"]
    baseline_metrics = selected["mean_pool_validation"]
    gates = {
        "macro_f1_at_least_0_65": selected_metrics["macro_f1"] >= 0.65,
        "coarse_accuracy_at_least_0_80": selected_metrics["coarse_accuracy"] >= 0.80,
        "macro_f1_exceeds_mean_pool": selected_metrics["macro_f1"]
        > baseline_metrics["macro_f1"],
        "all_reported_metrics_finite": _all_numeric_values_finite(
            {
                "validation": selected_metrics,
                "review_policy": selected["review_policy"],
                "mean_pool_validation": baseline_metrics,
                "majority_class_validation": selected["majority_class_validation"],
            }
        ),
        "review_accuracy_target_met": selected["review_policy"]["selection_status"]
        == "target_met",
        "selective_coverage_at_least_0_30": selected["review_policy"]["coverage"]
        >= 0.30,
    }
    report = {
        "schema_version": 1,
        "selection_split": "validation",
        "test_loaded": False,
        "seed": args.seed,
        "selected": selected["name"],
        "selection_rule": [
            "highest validation seven-class macro F1",
            "highest validation coarse accuracy",
            "lowest validation 10-bin expected calibration error",
            "smallest hidden dimension",
        ],
        "gate_margins": {
            "macro_f1_minus_0_65": selected_metrics["macro_f1"] - 0.65,
            "coarse_accuracy_minus_0_80": selected_metrics["coarse_accuracy"] - 0.80,
            "macro_f1_minus_mean_pool": selected_metrics["macro_f1"]
            - baseline_metrics["macro_f1"],
            "selective_coverage_minus_0_30": selected["review_policy"]["coverage"]
            - 0.30,
        },
        "advancement_gates": gates,
        "advance_to_locked_test": all(gates.values()),
        "candidates": rows,
    }
    write_json_atomic(args.output_root / "validation_grid.json", report)
    validation_grid_sha256 = sha256(args.output_root / "validation_grid.json")
    write_json_atomic(
        args.output_root / "selected_model.json",
        {
            "schema_version": 2,
            "selection_split": "validation",
            "test_loaded": False,
            "candidate": selected["name"],
            "candidate_path": str((args.output_root / selected["name"]).resolve()),
            "artifact_sha256": selected["artifact_sha256"],
            "model_config_sha256": selected["model_config_sha256"],
            "evaluation_sha256": selected["evaluation_sha256"],
            "mean_pool_baselines_sha256": selected["mean_pool_baselines_sha256"],
            "validation_grid_sha256": validation_grid_sha256,
            "advance_to_locked_test": report["advance_to_locked_test"],
            "advancement_gates": gates,
        },
    )
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
