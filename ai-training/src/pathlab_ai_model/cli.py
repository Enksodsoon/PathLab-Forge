"""Command-line interface for PathLab model training and inference."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .inference import PathLabPredictor
from .training import TrainingConfig, train_bracs_model


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="pathlab-ai-model")
    commands = parser.add_subparsers(dest="command", required=True)
    train = commands.add_parser("train-bracs")
    train.add_argument("--views-root", required=True, type=Path)
    train.add_argument("--output-root", required=True, type=Path)
    train.add_argument("--batch-size", type=int, default=64)
    train.add_argument("--threads", type=int, default=6)
    train.add_argument("--bootstrap-iterations", type=int, default=500)
    image = commands.add_parser("predict-image")
    image.add_argument("--model-root", required=True, type=Path)
    image.add_argument("--image", required=True, type=Path)
    slide = commands.add_parser("predict-slide")
    slide.add_argument("--model-root", required=True, type=Path)
    slide.add_argument("--slide", required=True, type=Path)
    slide.add_argument("--output", required=True, type=Path)
    slide.add_argument("--tile-size", type=int, default=1_024)
    slide.add_argument("--max-tiles", type=int, default=400)
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
    else:
        raise AssertionError(f"unhandled command: {args.command}")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
