"""Command-line interface for PathLab model training and inference."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .inference import PathLabMilPredictor, PathLabPredictor
from .mil import (
    MilTestEvaluationConfig,
    MilTrainingConfig,
    evaluate_bracs_mil_test,
    train_bracs_mil,
)
from .training import TrainingConfig, train_bracs_model
from .wsi_bags import (
    BagExtractionConfig,
    build_bracs_development_inventory,
    build_bracs_final_inventory,
    build_bracs_roi_slide_bags,
    build_bracs_wsi_inventory,
    extract_bracs_wsi_bags,
    select_bracs_wsi_cohort,
    shard_bracs_wsi_inventory,
    verify_bracs_wsi_bags,
    verify_slide_feature_repeatability,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="pathlab-ai-model")
    commands = parser.add_subparsers(dest="command", required=True)
    train = commands.add_parser("train-bracs")
    train.add_argument("--views-root", required=True, type=Path)
    train.add_argument("--output-root", required=True, type=Path)
    train.add_argument("--batch-size", type=int, default=64)
    train.add_argument("--threads", type=int, default=6)
    train.add_argument("--bootstrap-iterations", type=int, default=500)
    train.add_argument(
        "--encoder",
        choices=("mobilenet", "kaiko-vits16", "fusion"),
        default="mobilenet",
    )
    inventory = commands.add_parser("inventory-bracs-wsi")
    inventory.add_argument("--summary", required=True, type=Path)
    inventory.add_argument("--output", required=True, type=Path)
    cohort = commands.add_parser("select-bracs-wsi-cohort")
    cohort.add_argument("--inventory", required=True, type=Path)
    cohort.add_argument("--output", required=True, type=Path)
    cohort.add_argument("--train-per-class", required=True, type=int)
    cohort.add_argument("--validation-per-class", required=True, type=int)
    cohort.add_argument("--test-per-class", type=int, default=0)
    final_inventory = commands.add_parser("build-bracs-final-inventory")
    final_inventory.add_argument("--development-inventory", required=True, type=Path)
    final_inventory.add_argument("--full-inventory", required=True, type=Path)
    final_inventory.add_argument("--output", required=True, type=Path)
    development_inventory = commands.add_parser("build-bracs-development-inventory")
    development_inventory.add_argument("--full-inventory", required=True, type=Path)
    development_inventory.add_argument("--output", required=True, type=Path)
    shard_inventory = commands.add_parser("shard-bracs-wsi-inventory")
    shard_inventory.add_argument("--inventory", required=True, type=Path)
    shard_inventory.add_argument("--output-directory", required=True, type=Path)
    shard_inventory.add_argument("--shards", required=True, type=int)
    roi_bags = commands.add_parser("build-bracs-roi-slide-bags")
    roi_bags.add_argument("--views-root", required=True, type=Path)
    roi_bags.add_argument("--roi-manifest", required=True, type=Path)
    roi_bags.add_argument("--feature-cache", required=True, type=Path)
    roi_bags.add_argument("--bags-root", required=True, type=Path)
    bags = commands.add_parser("extract-bracs-wsi-bags")
    bags.add_argument("--inventory", required=True, type=Path)
    bags.add_argument("--bags-root", required=True, type=Path)
    bags.add_argument("--temporary-root", required=True, type=Path)
    bags.add_argument(
        "--splits",
        nargs="+",
        choices=("train", "validation", "test"),
        default=("train", "validation", "test"),
    )
    bags.add_argument("--max-tiles", type=int, default=128)
    bags.add_argument("--target-mpp", type=float, default=0.5)
    bags.add_argument("--batch-size", type=int, default=16)
    bags.add_argument("--threads", type=int, default=6)
    bags.add_argument("--limit", type=int)
    bags.add_argument("--slide-ids", nargs="*", default=())
    bags.add_argument("--keep-downloads", action="store_true")
    bags.add_argument("--defer-manifest", action="store_true")
    verify_bags = commands.add_parser("verify-bracs-wsi-bags")
    verify_bags.add_argument("--inventory", required=True, type=Path)
    verify_bags.add_argument("--bags-root", required=True, type=Path)
    repeatability = commands.add_parser("verify-slide-feature-repeatability")
    repeatability.add_argument("--slide", required=True, type=Path)
    repeatability.add_argument("--output", required=True, type=Path)
    repeatability.add_argument("--target-mpp", type=float, default=0.5)
    repeatability.add_argument("--max-tiles", type=int, default=16)
    repeatability.add_argument("--batch-size", type=int, default=8)
    mil = commands.add_parser("train-bracs-mil")
    mil.add_argument("--bags-root", required=True, type=Path)
    mil.add_argument("--output-root", required=True, type=Path)
    mil.add_argument("--hidden-dim", type=int, default=128)
    mil.add_argument("--learning-rate", type=float, default=1e-3)
    mil.add_argument("--weight-decay", type=float, default=1e-4)
    mil.add_argument("--max-epochs", type=int, default=200)
    mil.add_argument("--patience", type=int, default=25)
    mil.add_argument("--bootstrap-iterations", type=int, default=500)
    mil.add_argument("--seed", type=int, default=20260802)
    mil.add_argument(
        "--validation-only",
        action="store_true",
        help="select and calibrate on validation without loading or evaluating test bags",
    )
    mil_test = commands.add_parser("evaluate-bracs-mil-test")
    mil_test.add_argument("--bags-root", required=True, type=Path)
    mil_test.add_argument("--model-root", required=True, type=Path)
    mil_test.add_argument("--bootstrap-iterations", type=int, default=500)
    mil_test.add_argument("--seed", type=int, default=20260802)
    mil_test.add_argument("--expected-test-slides", type=int, default=87)
    mil_test.add_argument("--expected-test-patients", type=int, default=31)
    image = commands.add_parser("predict-image")
    image.add_argument("--model-root", required=True, type=Path)
    image.add_argument("--image", required=True, type=Path)
    slide = commands.add_parser("predict-slide")
    slide.add_argument("--model-root", required=True, type=Path)
    slide.add_argument("--slide", required=True, type=Path)
    slide.add_argument("--output", required=True, type=Path)
    slide.add_argument("--tile-size", type=int, default=1_024)
    slide.add_argument("--max-tiles", type=int, default=400)
    mil_slide = commands.add_parser("predict-slide-mil")
    mil_slide.add_argument("--model-root", required=True, type=Path)
    mil_slide.add_argument("--slide", required=True, type=Path)
    mil_slide.add_argument("--output", required=True, type=Path)
    mil_slide.add_argument("--target-mpp", type=float)
    mil_slide.add_argument("--max-tiles", type=int)
    mil_slide.add_argument("--batch-size", type=int, default=16)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "train-bracs":
        result = train_bracs_model(
            TrainingConfig(
                views_root=args.views_root,
                output_root=args.output_root,
                batch_size=args.batch_size,
                threads=args.threads,
                bootstrap_iterations=args.bootstrap_iterations,
                encoder=args.encoder,
            )
        )
    elif args.command == "inventory-bracs-wsi":
        result = build_bracs_wsi_inventory(args.summary, args.output)
    elif args.command == "select-bracs-wsi-cohort":
        result = select_bracs_wsi_cohort(
            args.inventory,
            args.output,
            train_per_class=args.train_per_class,
            validation_per_class=args.validation_per_class,
            test_per_class=args.test_per_class,
        )
    elif args.command == "build-bracs-roi-slide-bags":
        result = build_bracs_roi_slide_bags(
            args.views_root, args.roi_manifest, args.feature_cache, args.bags_root
        )
    elif args.command == "build-bracs-final-inventory":
        result = build_bracs_final_inventory(
            args.development_inventory, args.full_inventory, args.output
        )
    elif args.command == "build-bracs-development-inventory":
        result = build_bracs_development_inventory(args.full_inventory, args.output)
    elif args.command == "shard-bracs-wsi-inventory":
        result = shard_bracs_wsi_inventory(
            args.inventory, args.output_directory, shards=args.shards
        )
    elif args.command == "extract-bracs-wsi-bags":
        result = extract_bracs_wsi_bags(
            BagExtractionConfig(
                inventory_path=args.inventory,
                bags_root=args.bags_root,
                temporary_root=args.temporary_root,
                splits=tuple(args.splits),
                max_tiles=args.max_tiles,
                target_mpp=args.target_mpp,
                batch_size=args.batch_size,
                threads=args.threads,
                limit=args.limit,
                slide_ids=tuple(args.slide_ids),
                keep_downloads=args.keep_downloads,
                rebuild_manifest=not args.defer_manifest,
            )
        )
    elif args.command == "train-bracs-mil":
        result = train_bracs_mil(
            MilTrainingConfig(
                bags_root=args.bags_root,
                output_root=args.output_root,
                hidden_dim=args.hidden_dim,
                learning_rate=args.learning_rate,
                weight_decay=args.weight_decay,
                max_epochs=args.max_epochs,
                patience=args.patience,
                bootstrap_iterations=args.bootstrap_iterations,
                seed=args.seed,
                validation_only=args.validation_only,
            )
        )
    elif args.command == "verify-bracs-wsi-bags":
        result = verify_bracs_wsi_bags(args.inventory, args.bags_root)
    elif args.command == "verify-slide-feature-repeatability":
        result = verify_slide_feature_repeatability(
            args.slide,
            args.output,
            target_mpp=args.target_mpp,
            max_tiles=args.max_tiles,
            batch_size=args.batch_size,
        )
    elif args.command == "evaluate-bracs-mil-test":
        result = evaluate_bracs_mil_test(
            MilTestEvaluationConfig(
                bags_root=args.bags_root,
                model_root=args.model_root,
                bootstrap_iterations=args.bootstrap_iterations,
                seed=args.seed,
                expected_test_slides=args.expected_test_slides,
                expected_test_patients=args.expected_test_patients,
                require_selection_record=True,
            )
        )
    elif args.command == "predict-image":
        result = PathLabPredictor(args.model_root).predict_path(args.image)
    elif args.command == "predict-slide":
        result = PathLabPredictor(args.model_root).predict_slide(
            args.slide,
            output_path=args.output,
            tile_size=args.tile_size,
            max_tiles=args.max_tiles,
        )
    elif args.command == "predict-slide-mil":
        result = PathLabMilPredictor(args.model_root).predict_slide(
            args.slide,
            output_path=args.output,
            target_mpp=args.target_mpp,
            max_tiles=args.max_tiles,
            batch_size=args.batch_size,
        )
    else:
        raise AssertionError(f"unhandled command: {args.command}")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
