"""Command-line entry point for PathLab AI dataset preparation."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .bracs import (
    LABELS,
    OFFICIAL_CLASS_COUNTS,
    OFFICIAL_PATIENT_SPLIT_COUNTS,
    OFFICIAL_SPLIT_CLASS_COUNTS,
    OFFICIAL_SPLIT_COUNTS,
    BracsPreparationConfig,
    PreparationError,
    prepare_bracs,
)
from .bracs_roi import BracsRoiPreparationConfig, RoiPreparationError, prepare_bracs_roi


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="pathlab-ai-data")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser(
        "prepare-bracs",
        help="validate the authorized BRACS archive and create train-ready manifests",
    )
    prepare.add_argument("--raw-root", required=True, type=Path)
    prepare.add_argument("--summary", required=True, type=Path)
    prepare.add_argument("--output-root", required=True, type=Path)
    prepare.add_argument("--min-slide-bytes", type=int, default=1_048_576)
    prepare.add_argument("--minimum-dimension", type=int, default=1_024)
    prepare.add_argument("--minimum-tissue-fraction", type=float, default=0.01)
    prepare.add_argument(
        "--probe-mode",
        choices=("tiffslide", "header"),
        default="tiffslide",
        help="decoded tiffslide QC is required for real training; header is for fixtures only",
    )
    prepare.add_argument(
        "--allow-subset",
        action="store_true",
        help="disable the official 547-slide distribution checks for development fixtures",
    )
    prepare_roi = commands.add_parser(
        "prepare-bracs-roi",
        help="validate BRACS ROI PNGs and create patient-safe training manifests",
    )
    prepare_roi.add_argument("--raw-root", required=True, type=Path)
    prepare_roi.add_argument("--summary", required=True, type=Path)
    prepare_roi.add_argument("--output-root", required=True, type=Path)
    prepare_roi.add_argument("--minimum-dimension", type=int, default=64)
    prepare_roi.add_argument("--minimum-file-bytes", type=int, default=1_024)
    prepare_roi.add_argument("--allow-subset", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "prepare-bracs-roi":
        try:
            report = prepare_bracs_roi(
                BracsRoiPreparationConfig(
                    raw_root=args.raw_root,
                    summary_path=args.summary,
                    output_root=args.output_root,
                    enforce_official_counts=not args.allow_subset,
                    minimum_dimension=args.minimum_dimension,
                    minimum_file_bytes=args.minimum_file_bytes,
                )
            )
        except RoiPreparationError as error:
            print(f"BRACS ROI preparation failed: {error}", file=sys.stderr)
            return 2
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0
    if args.command != "prepare-bracs":
        raise AssertionError(f"unhandled command: {args.command}")
    official = not args.allow_subset
    config = BracsPreparationConfig(
        raw_root=args.raw_root,
        summary_path=args.summary,
        output_root=args.output_root,
        expected_slides=547 if official else None,
        expected_patients=189 if official else None,
        expected_class_counts=OFFICIAL_CLASS_COUNTS if official else None,
        expected_split_counts=OFFICIAL_SPLIT_COUNTS if official else None,
        expected_patient_split_counts=(
            OFFICIAL_PATIENT_SPLIT_COUNTS if official else None
        ),
        expected_split_class_counts=(OFFICIAL_SPLIT_CLASS_COUNTS if official else None),
        required_labels=tuple(LABELS) if official else (),
        min_slide_bytes=args.min_slide_bytes,
        minimum_dimension=args.minimum_dimension,
        minimum_tissue_fraction=args.minimum_tissue_fraction,
        probe_mode=args.probe_mode,
    )
    try:
        report = prepare_bracs(config)
    except PreparationError as error:
        print(f"BRACS preparation failed: {error}", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
