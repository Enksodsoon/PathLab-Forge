from __future__ import annotations

import hashlib
import json
import random
import uuid
from pathlib import Path
from typing import Any

import numpy
import tifffile
from PIL import Image, ImageDraw, ImageFilter


SYNTHETIC_ARTIFACTS = (
    "blank",
    "blur",
    "bubble",
    "color_shift",
    "compression",
    "fold",
    "pen_mark",
)


def _base_image(width: int, height: int, seed: int) -> Image.Image:
    randomizer = random.Random(seed)
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image, "RGBA")
    for _ in range(max(20, width * height // 4_096)):
        x = randomizer.randrange(width)
        y = randomizer.randrange(height)
        radius = randomizer.randrange(8, max(9, min(width, height) // 10))
        color = randomizer.choice(((205, 90, 145, 90), (105, 65, 155, 75), (235, 155, 185, 80)))
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=color)
    return image


def _apply(image: Image.Image, artifact: str) -> Image.Image:
    output = image.copy()
    draw = ImageDraw.Draw(output, "RGBA")
    width, height = output.size
    if artifact == "blank":
        draw.rectangle((width // 2, 0, width, height), fill=(255, 255, 255, 255))
    elif artifact == "blur":
        blurred = output.crop((0, 0, width // 2, height)).filter(ImageFilter.GaussianBlur(8))
        output.paste(blurred, (0, 0))
    elif artifact == "bubble":
        draw.ellipse((width // 3, height // 3, 2 * width // 3, 2 * height // 3), outline=(245, 245, 255, 230), width=max(3, width // 80))
    elif artifact == "color_shift":
        red, green, blue = output.split()
        output = Image.merge("RGB", (red.point(lambda value: min(255, int(value * 1.15))), green, blue.point(lambda value: int(value * 0.8))))
    elif artifact == "compression":
        output = output.resize((max(1, width // 12), max(1, height // 12))).resize((width, height), Image.Resampling.NEAREST)
    elif artifact == "fold":
        draw.polygon(((width // 4, 0), (width // 2, 0), (3 * width // 4, height), (width // 2, height)), fill=(115, 45, 145, 90))
    elif artifact == "pen_mark":
        draw.line(((20, height // 2), (width // 3, height // 3), (2 * width // 3, 2 * height // 3), (width - 20, height // 2)), fill=(25, 60, 210, 255), width=max(4, width // 64))
    else:
        raise ValueError(f"Unsupported synthetic artifact: {artifact}")
    return output


def generate_suite(output_directory: Path, *, width: int = 512, height: int = 384, seed: int = 17) -> dict[str, Any]:
    if width < 128 or height < 128 or width > 4_096 or height > 4_096:
        raise ValueError("Synthetic fixture dimensions must remain between 128 and 4096 pixels.")
    output_directory.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, Any]] = []
    for index, artifact in enumerate(SYNTHETIC_ARTIFACTS):
        image = _apply(_base_image(width, height, seed), artifact)
        path = output_directory / f"synthetic-{artifact}.ome.tif"
        levels = [image]
        while min(levels[-1].size) > 128 and len(levels) < 4:
            current_width, current_height = levels[-1].size
            levels.append(
                levels[-1].resize(
                    (max(1, current_width // 2), max(1, current_height // 2)),
                    Image.Resampling.BILINEAR,
                )
            )
        with tifffile.TiffWriter(path, bigtiff=True, ome=True) as writer:
            writer.write(
                numpy.asarray(levels[0]),
                photometric="rgb",
                tile=(128, 128),
                compression="deflate",
                subifds=len(levels) - 1,
                resolution=(40_000, 40_000),
                resolutionunit="CENTIMETER",
                metadata={
                    "axes": "YXS",
                    "Name": f"synthetic-{artifact}",
                    "PhysicalSizeX": 0.25,
                    "PhysicalSizeXUnit": "µm",
                    "PhysicalSizeY": 0.25,
                    "PhysicalSizeYUnit": "µm",
                    "UUID": str(
                        uuid.uuid5(
                            uuid.NAMESPACE_URL,
                            f"pathlab.synthetic-pathology/v1/{seed}/{width}/{height}/{artifact}",
                        )
                    ),
                },
            )
            for level_index, level in enumerate(levels[1:], start=1):
                writer.write(
                    numpy.asarray(level),
                    photometric="rgb",
                    tile=(min(128, level.height), min(128, level.width)),
                    compression="deflate",
                    subfiletype=1,
                    resolution=(40_000 / (2**level_index),) * 2,
                    resolutionunit="CENTIMETER",
                )
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        records.append({"artifact": artifact, "path": path.name, "sha256": digest, "synthetic": True, "diagnostic_evidence": False})
    corrupt_source = output_directory / "synthetic-blur.ome.tif"
    corrupt_path = output_directory / "synthetic-corrupt-pyramid.ome.tif"
    content = corrupt_source.read_bytes()
    corrupt_path.write_bytes(content[: max(64, len(content) // 3)])
    records.append({"artifact": "corrupt_pyramid", "path": corrupt_path.name, "sha256": hashlib.sha256(corrupt_path.read_bytes()).hexdigest(), "synthetic": True, "diagnostic_evidence": False, "expected_failure": True})
    manifest = {
        "schema": "pathlab.synthetic-pathology/1",
        "research_only": True,
        "not_diagnostic": True,
        "seed": seed,
        "width": width,
        "height": height,
        "fixtures": records,
    }
    (output_directory / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest
