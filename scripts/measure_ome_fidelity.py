"""Compare sampled direct OME pixels with lossless Bio-Formats source crops."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

import numpy as np
import pyvips

MINIMUM_SSIM = 0.970
MAXIMUM_MEAN_DELTA_E00 = 2.5
MINIMUM_EDGE_DETAIL_RETENTION = 0.90


def rgb8(image: pyvips.Image) -> np.ndarray:
    normalized = image.colourspace("srgb").cast("uchar")
    if normalized.bands > 3:
        normalized = normalized.extract_band(0, n=3)
    return np.frombuffer(normalized.write_to_memory(), dtype=np.uint8).reshape(
        normalized.height, normalized.width, normalized.bands
    ).copy()


def edge_detail_retention(reference: np.ndarray, candidate: np.ndarray) -> float:
    weights = np.array([0.2126, 0.7152, 0.0722], dtype=np.float64)
    reference_luma = reference[..., :3].astype(np.float64) @ weights
    candidate_luma = candidate[..., :3].astype(np.float64) @ weights

    def energy(image: np.ndarray) -> float:
        gx = image[1:-1, 2:] - image[1:-1, :-2]
        gy = image[2:, 1:-1] - image[:-2, 1:-1]
        return float(np.hypot(gx, gy).sum())

    reference_energy = energy(reference_luma)
    if reference_energy < 1e-6:
        return 1.0
    return min(1.0, energy(candidate_luma) / reference_energy)


def require_private_evidence_path(path: Path) -> Path:
    resolved = path.resolve()
    for parent in (resolved.parent, *resolved.parents):
        if (parent / ".git").exists():
            raise ValueError("Fidelity evidence must be written outside every Git repository")
    return resolved


def plan_native_rois(candidate: pyvips.Image, width: int, height: int) -> tuple[tuple[int, int], ...]:
    roi_size = 256
    if width < roi_size or height < roi_size:
        raise ValueError("Fidelity source is too small for native quality ROIs")
    overview = rgb8(candidate.thumbnail_image(1024))
    luminance = overview[..., :3].astype(np.float64) @ np.array(
        [0.2126, 0.7152, 0.0722], dtype=np.float64
    )
    candidates: list[tuple[tuple[int, int], float, float]] = []

    def centered(center_x: int, center_y: int) -> tuple[int, int]:
        return (
            max(0, min(width - roi_size, center_x - roi_size // 2)),
            max(0, min(height - roi_size, center_y - roi_size // 2)),
        )

    for row in range(8):
        top = row * overview.shape[0] // 8
        bottom = max(top + 1, (row + 1) * overview.shape[0] // 8)
        for column in range(8):
            left = column * overview.shape[1] // 8
            right = max(left + 1, (column + 1) * overview.shape[1] // 8)
            sector = luminance[top:bottom, left:right]
            center_x = (left + right) * width // (2 * overview.shape[1])
            center_y = (top + bottom) * height // (2 * overview.shape[0])
            candidates.append((centered(center_x, center_y), float(sector.mean()), float(sector.var())))

    selected: dict[tuple[int, int], None] = {}

    def add_ranked(rows: list[tuple[tuple[int, int], float, float]], target: int) -> None:
        for coordinate, _, _ in rows:
            if len(selected) >= target:
                return
            selected.setdefault(coordinate, None)

    add_ranked(sorted(candidates, key=lambda row: row[1]), 16)
    add_ranked(sorted(candidates, key=lambda row: row[1], reverse=True), 32)
    add_ranked(sorted(candidates, key=lambda row: row[2], reverse=True), 48)
    for index in range(32):
        if len(selected) >= 64:
            break
        seam_x = max(512, min(width - 1, round((index + 1) * width / 17 / 512) * 512))
        center_y = round(((index % 8) + 0.5) * height / 8)
        selected.setdefault(centered(seam_x, center_y), None)
    add_ranked(candidates, 64)
    if len(selected) < 64:
        raise ValueError("Could not derive 64 distinct native quality ROIs")
    return tuple(selected)[:64]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--series", type=int, required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--source-x", type=int, default=0)
    parser.add_argument("--source-y", type=int, default=0)
    parser.add_argument("--bftools", type=Path, required=True)
    parser.add_argument("--benchmark-repo", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    arguments = parser.parse_args()
    evidence_path = require_private_evidence_path(arguments.evidence)

    sys.path.insert(0, str(arguments.benchmark_repo / "src"))
    from pathlab_bench.metrics.objective import compare_pixels

    candidate = pyvips.Image.new_from_file(str(arguments.candidate), access="random")
    edge = 256
    coordinates = plan_native_rois(candidate, arguments.width, arguments.height)
    measurements: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="pathlab-ome-fidelity-") as temporary:
        temporary_root = Path(temporary)
        for index, (x, y) in enumerate(coordinates):
            reference_path = temporary_root / f"reference-{index}.ome.tif"
            command = [
                "java",
                "-Xmx1g",
                "-cp",
                str(arguments.bftools / "*"),
                "loci.formats.tools.ImageConverter",
                "-no-upgrade",
                "-series",
                str(arguments.series),
                "-merge",
                "-expand",
                "-bigtiff",
                "-compression",
                "LZW",
                "-no-sas",
                "-crop",
                f"{arguments.source_x + x},{arguments.source_y + y},{edge},{edge}",
                "-option",
                "cellsens.fail_on_missing_ets",
                "true",
                str(arguments.source),
                str(reference_path),
            ]
            conversion = subprocess.run(
                command,
                capture_output=True,
                check=False,
                text=True,
                timeout=600,
            )
            if conversion.returncode != 0 or not reference_path.is_file():
                raise RuntimeError("Bio-Formats lossless reference crop failed")
            reference = rgb8(pyvips.Image.new_from_file(str(reference_path), access="sequential"))
            encoded = rgb8(candidate.crop(x, y, edge, edge))
            if reference.shape != encoded.shape:
                raise RuntimeError(
                    f"Reference geometry {reference.shape} differs from candidate {encoded.shape}"
                )
            metrics = compare_pixels(reference, encoded, seam_size=512)
            measurements.append(
                {
                    "x": x,
                    "y": y,
                    "width": edge,
                    "height": edge,
                    "ssim": metrics.ssim,
                    "meanDeltaE00": metrics.mean_delta_e,
                    "p95DeltaE00": metrics.p95_delta_e,
                    "seamExcess": metrics.seam_excess,
                    "psnrDb": metrics.psnr_db,
                    "edgeDetailRetention": edge_detail_retention(reference, encoded),
                }
            )

    thresholds = {
        "minimum_ssim": MINIMUM_SSIM,
        "maximum_mean_delta_e": MAXIMUM_MEAN_DELTA_E00,
        "minimum_edge_detail_retention": MINIMUM_EDGE_DETAIL_RETENTION,
    }
    summary = {
        "minimumSsim": min(row["ssim"] for row in measurements),
        "maximumMeanDeltaE00": max(row["meanDeltaE00"] for row in measurements),
        "maximumP95DeltaE00": max(row["p95DeltaE00"] for row in measurements),
        "maximumSeamExcess": max(row["seamExcess"] for row in measurements),
        "minimumEdgeDetailRetention": min(
            row["edgeDetailRetention"] for row in measurements
        ),
    }
    summary["passed"] = (
        summary["minimumSsim"] >= thresholds["minimum_ssim"]
        and summary["maximumMeanDeltaE00"] <= thresholds["maximum_mean_delta_e"]
        and summary["minimumEdgeDetailRetention"]
        >= thresholds["minimum_edge_detail_retention"]
    )
    document = {
        "schema": "pathlab-private-ome-fidelity/v1",
        "qualityProfile": "pathlab-visual-v1",
        "measurements": measurements,
        "summary": summary,
        "thresholds": thresholds,
    }
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
