"""Deterministically compare maximum-level DZI tiles with their OME-TIFF pixels."""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vips", required=True, type=Path)
    parser.add_argument("--ome", required=True, type=Path)
    parser.add_argument("--dzi-root", required=True, type=Path)
    parser.add_argument("--width", required=True, type=int)
    parser.add_argument("--height", required=True, type=int)
    parser.add_argument("--minimum-ssim", default=0.97, type=float)
    parser.add_argument("--maximum-delta-e00", default=3.0, type=float)
    args = parser.parse_args()
    level = math.ceil(math.log2(max(args.width, args.height)))
    columns = math.ceil(args.width / 512)
    rows = math.ceil(args.height / 512)
    samples = sorted(
        {
            (0, 0),
            (columns - 1, 0),
            (columns // 2, rows // 2),
            (0, rows - 1),
            (columns - 1, rows - 1),
        }
    )
    results: list[dict[str, float | int]] = []
    with tempfile.TemporaryDirectory(prefix="pathlab-quality-") as temporary:
        temporary_root = Path(temporary)
        for column, row in samples:
            left_overlap = 1 if column > 0 else 0
            top_overlap = 1 if row > 0 else 0
            right_overlap = 1 if (column + 1) * 512 < args.width else 0
            bottom_overlap = 1 if (row + 1) * 512 < args.height else 0
            x = column * 512 - left_overlap
            y = row * 512 - top_overlap
            width = min(512, args.width - column * 512) + left_overlap + right_overlap
            height = min(512, args.height - row * 512) + top_overlap + bottom_overlap
            reference = temporary_root / f"{column}_{row}.png"
            subprocess.run(
                [
                    str(args.vips),
                    "crop",
                    str(args.ome),
                    str(reference),
                    str(x),
                    str(y),
                    str(width),
                    str(height),
                ],
                check=True,
                capture_output=True,
            )
            tile = args.dzi_root / "slide_files" / str(level) / f"{column}_{row}.jpg"
            expected = np.asarray(Image.open(reference).convert("RGB"), dtype=np.float64)
            actual = np.asarray(Image.open(tile).convert("RGB"), dtype=np.float64)
            if expected.shape != actual.shape:
                raise ValueError(
                    f"Tile {column},{row} shape mismatch: {expected.shape} != {actual.shape}"
                )
            results.append(
                {
                    "column": column,
                    "row": row,
                    "ssim": ssim(expected, actual),
                    "meanDeltaE00": float(
                        np.mean(delta_e_2000(rgb_to_lab(expected), rgb_to_lab(actual)))
                    ),
                }
            )
    summary = {
        "level": level,
        "samples": results,
        "minimumSsim": min(float(item["ssim"]) for item in results),
        "meanSsim": float(np.mean([item["ssim"] for item in results])),
        "maximumMeanDeltaE00": max(float(item["meanDeltaE00"]) for item in results),
        "meanDeltaE00": float(np.mean([item["meanDeltaE00"] for item in results])),
    }
    summary["passed"] = (
        summary["minimumSsim"] >= args.minimum_ssim
        and summary["maximumMeanDeltaE00"] <= args.maximum_delta_e00
    )
    print(json.dumps(summary, indent=2))
    if not summary["passed"]:
        raise SystemExit(1)


def ssim(first: np.ndarray, second: np.ndarray) -> float:
    first_channels = first.reshape(-1, 3)
    second_channels = second.reshape(-1, 3)
    means_first = first_channels.mean(axis=0)
    means_second = second_channels.mean(axis=0)
    variance_first = first_channels.var(axis=0)
    variance_second = second_channels.var(axis=0)
    covariance = np.mean(
        (first_channels - means_first) * (second_channels - means_second), axis=0
    )
    c1 = (0.01 * 255) ** 2
    c2 = (0.03 * 255) ** 2
    values = (
        (2 * means_first * means_second + c1)
        * (2 * covariance + c2)
        / (
            (means_first**2 + means_second**2 + c1)
            * (variance_first + variance_second + c2)
        )
    )
    return float(values.mean())


def rgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    linear = rgb / 255
    linear = np.where(
        linear <= 0.04045,
        linear / 12.92,
        ((linear + 0.055) / 1.055) ** 2.4,
    )
    xyz = linear @ np.array(
        [
            [0.4124564, 0.3575761, 0.1804375],
            [0.2126729, 0.7151522, 0.0721750],
            [0.0193339, 0.1191920, 0.9503041],
        ]
    ).T
    xyz /= np.array([0.95047, 1.0, 1.08883])
    epsilon = 216 / 24389
    kappa = 24389 / 27
    f = np.where(xyz > epsilon, np.cbrt(xyz), (kappa * xyz + 16) / 116)
    return np.stack(
        (116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])),
        axis=-1,
    )


def delta_e_2000(lab1: np.ndarray, lab2: np.ndarray) -> np.ndarray:
    l1, a1, b1 = np.moveaxis(lab1, -1, 0)
    l2, a2, b2 = np.moveaxis(lab2, -1, 0)
    c1 = np.hypot(a1, b1)
    c2 = np.hypot(a2, b2)
    c_bar = (c1 + c2) / 2
    g = 0.5 * (1 - np.sqrt(c_bar**7 / (c_bar**7 + 25**7)))
    a1_prime = (1 + g) * a1
    a2_prime = (1 + g) * a2
    c1_prime = np.hypot(a1_prime, b1)
    c2_prime = np.hypot(a2_prime, b2)
    h1_prime = np.mod(np.degrees(np.arctan2(b1, a1_prime)), 360)
    h2_prime = np.mod(np.degrees(np.arctan2(b2, a2_prime)), 360)
    delta_l = l2 - l1
    delta_c = c2_prime - c1_prime
    delta_h_angle = h2_prime - h1_prime
    delta_h_angle = np.where(delta_h_angle > 180, delta_h_angle - 360, delta_h_angle)
    delta_h_angle = np.where(delta_h_angle < -180, delta_h_angle + 360, delta_h_angle)
    delta_h_angle = np.where(c1_prime * c2_prime == 0, 0, delta_h_angle)
    delta_h = 2 * np.sqrt(c1_prime * c2_prime) * np.sin(
        np.radians(delta_h_angle / 2)
    )
    l_bar = (l1 + l2) / 2
    c_bar_prime = (c1_prime + c2_prime) / 2
    h_sum = h1_prime + h2_prime
    h_difference = np.abs(h1_prime - h2_prime)
    h_bar = np.where(
        c1_prime * c2_prime == 0,
        h_sum,
        np.where(
            h_difference <= 180,
            h_sum / 2,
            np.where(h_sum < 360, (h_sum + 360) / 2, (h_sum - 360) / 2),
        ),
    )
    t = (
        1
        - 0.17 * np.cos(np.radians(h_bar - 30))
        + 0.24 * np.cos(np.radians(2 * h_bar))
        + 0.32 * np.cos(np.radians(3 * h_bar + 6))
        - 0.20 * np.cos(np.radians(4 * h_bar - 63))
    )
    delta_theta = 30 * np.exp(-((h_bar - 275) / 25) ** 2)
    r_c = 2 * np.sqrt(c_bar_prime**7 / (c_bar_prime**7 + 25**7))
    s_l = 1 + 0.015 * (l_bar - 50) ** 2 / np.sqrt(20 + (l_bar - 50) ** 2)
    s_c = 1 + 0.045 * c_bar_prime
    s_h = 1 + 0.015 * c_bar_prime * t
    r_t = -np.sin(np.radians(2 * delta_theta)) * r_c
    return np.sqrt(
        (delta_l / s_l) ** 2
        + (delta_c / s_c) ** 2
        + (delta_h / s_h) ** 2
        + r_t * (delta_c / s_c) * (delta_h / s_h)
    )


if __name__ == "__main__":
    main()
