"""TorchScript inference for ordinary images and unannotated whole slides."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import numpy as np
import torch
from PIL import Image

from .core import COARSE_GROUP, LABELS, make_views, sha256, tensor_transform


class PathLabPredictor:
    def __init__(self, model_root: Path) -> None:
        self.model_root = model_root.resolve()
        self.config = json.loads(
            (self.model_root / "model_config.json").read_text(encoding="utf-8")
        )
        artifact = self.model_root / self.config["torchscript"]
        if sha256(artifact) != self.config["torchscript_sha256"]:
            raise ValueError("model artifact checksum does not match model_config.json")
        self.model = torch.jit.load(str(artifact), map_location="cpu").eval()
        self.transform = tensor_transform()

    def predict_image(self, image: Image.Image) -> dict[str, Any]:
        views = make_views(image)
        batch = torch.stack([self.transform(view) for view in views])
        with torch.inference_mode():
            logits = self.model(batch).mean(dim=0)
            probabilities = (
                torch.softmax(logits / float(self.config["temperature"]), dim=0)
                .cpu()
                .numpy()
            )
        prediction = int(probabilities.argmax())
        confidence = float(probabilities[prediction])
        label = LABELS[prediction]
        return {
            "label": label,
            "coarse_group": COARSE_GROUP[label],
            "confidence": confidence,
            "needs_review": confidence < float(self.config["review_threshold"]),
            "probabilities": {
                name: float(probabilities[index]) for index, name in enumerate(LABELS)
            },
            "model": self.config["model_name"],
            "intended_use": self.config["intended_use"],
        }

    def predict_path(self, image_path: Path) -> dict[str, Any]:
        with Image.open(image_path) as image:
            result = self.predict_image(image.convert("RGB"))
        result["source"] = str(image_path.resolve())
        return result

    def predict_slide(
        self,
        slide_path: Path,
        *,
        output_path: Path,
        tile_size: int = 1_024,
        max_tiles: int = 400,
        minimum_tissue_fraction: float = 0.10,
    ) -> dict[str, Any]:
        import tiffslide

        slide = tiffslide.TiffSlide(str(slide_path))
        width, height = slide.dimensions
        coordinates = [
            (x, y)
            for y in range(0, height, tile_size)
            for x in range(0, width, tile_size)
        ]
        if len(coordinates) > max_tiles:
            indices = np.linspace(0, len(coordinates) - 1, max_tiles, dtype=int)
            coordinates = [coordinates[index] for index in indices]
        tiles: list[dict[str, Any]] = []
        for x, y in coordinates:
            region = slide.read_region(
                (x, y), 0, (min(tile_size, width - x), min(tile_size, height - y))
            ).convert("RGB")
            tissue = _tissue_fraction(region)
            if tissue < minimum_tissue_fraction:
                continue
            prediction = self.predict_image(region)
            tiles.append(
                {
                    "x": x,
                    "y": y,
                    "width": region.width,
                    "height": region.height,
                    "tissue_fraction": tissue,
                    **prediction,
                }
            )
        slide.close()
        if not tiles:
            raise ValueError(
                "no tissue-containing tiles passed the configured threshold"
            )
        aggregate = {
            label: float(
                np.average(
                    [tile["probabilities"][label] for tile in tiles],
                    weights=[tile["tissue_fraction"] for tile in tiles],
                )
            )
            for label in LABELS
        }
        final_label = max(aggregate, key=aggregate.get)
        result = {
            "schema_version": 1,
            "source": str(slide_path.resolve()),
            "dimensions": [width, height],
            "tile_size": tile_size,
            "sampled_tiles": len(coordinates),
            "tissue_tiles": len(tiles),
            "aggregate_label": final_label,
            "aggregate_coarse_group": COARSE_GROUP[final_label],
            "aggregate_probabilities": aggregate,
            "tiles": tiles,
            "model": self.config["model_name"],
            "intended_use": self.config["intended_use"],
        }
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        return result


def _tissue_fraction(image: Image.Image) -> float:
    thumbnail = image.copy()
    thumbnail.thumbnail((64, 64), Image.Resampling.BILINEAR)
    pixels = np.asarray(thumbnail, dtype=np.float32) / 255.0
    maximum = pixels.max(axis=2)
    minimum = pixels.min(axis=2)
    saturation = (maximum - minimum) / np.maximum(maximum, 1e-6)
    tissue = (maximum < 0.94) & (saturation > 0.05)
    return float(tissue.mean())
