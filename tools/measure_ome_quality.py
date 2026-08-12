"""Compare deterministic rendered-RGB OME-TIFF samples against a reference export."""

from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

from measure_dzi_quality import delta_e_2000, rgb_to_lab, ssim


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vips", required=True, type=Path)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--width", required=True, type=int)
    parser.add_argument("--height", required=True, type=int)
    parser.add_argument("--minimum-ssim", default=0.97, type=float)
    parser.add_argument("--maximum-delta-e00", default=3.0, type=float)
    parser.add_argument("--alignment-tolerance", default=1, type=int)
    args = parser.parse_args()
    size = min(512, args.width, args.height)
    samples = sorted(
        {
            (0, 0),
            (args.width - size, 0),
            ((args.width - size) // 2, (args.height - size) // 2),
            (0, args.height - size),
            (args.width - size, args.height - size),
        }
    )
    results: list[dict[str, float | int]] = []
    with tempfile.TemporaryDirectory(prefix="pathlab-ome-quality-") as temporary:
        root = Path(temporary)
        for index, (x, y) in enumerate(samples):
            reference = crop(args.vips, args.reference, root / f"r{index}.png", x, y, size)
            comparisons = []
            for offset_y in range(-args.alignment_tolerance, args.alignment_tolerance + 1):
                for offset_x in range(-args.alignment_tolerance, args.alignment_tolerance + 1):
                    candidate_x = min(max(0, x + offset_x), args.width - size)
                    candidate_y = min(max(0, y + offset_y), args.height - size)
                    candidate = crop(
                        args.vips,
                        args.candidate,
                        root / f"c{index}-{offset_x}-{offset_y}.png",
                        candidate_x,
                        candidate_y,
                        size,
                    )
                    score = ssim(reference, candidate)
                    comparisons.append(
                        (
                            score,
                            float(
                                np.mean(
                                    delta_e_2000(
                                        rgb_to_lab(reference), rgb_to_lab(candidate)
                                    )
                                )
                            ),
                            candidate_x - x,
                            candidate_y - y,
                        )
                    )
            best = max(comparisons, key=lambda item: item[0])
            results.append(
                {
                    "x": x,
                    "y": y,
                    "offsetX": best[2],
                    "offsetY": best[3],
                    "ssim": best[0],
                    "meanDeltaE00": best[1],
                }
            )
    summary = {
        "samples": results,
        "minimumSsim": min(float(item["ssim"]) for item in results),
        "meanSsim": float(np.mean([item["ssim"] for item in results])),
        "maximumMeanDeltaE00": max(
            float(item["meanDeltaE00"]) for item in results
        ),
        "meanDeltaE00": float(
            np.mean([item["meanDeltaE00"] for item in results])
        ),
    }
    summary["passed"] = (
        summary["minimumSsim"] >= args.minimum_ssim
        and summary["maximumMeanDeltaE00"] <= args.maximum_delta_e00
    )
    print(json.dumps(summary, indent=2))
    if not summary["passed"]:
        raise SystemExit(1)


def crop(
    vips: Path, source: Path, output: Path, x: int, y: int, size: int
) -> np.ndarray:
    subprocess.run(
        [str(vips), "crop", str(source), str(output), str(x), str(y), str(size), str(size)],
        check=True,
        capture_output=True,
    )
    return np.asarray(Image.open(output).convert("RGB"), dtype=np.float64)


if __name__ == "__main__":
    main()
