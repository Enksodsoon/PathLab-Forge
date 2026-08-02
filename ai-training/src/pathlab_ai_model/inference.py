"""TorchScript inference for ordinary images and unannotated whole slides."""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import numpy as np
import torch
from PIL import Image

from .core import (
    COARSE_GROUP,
    LABELS,
    make_views,
    sha256,
    tensor_transform,
    write_json_atomic,
)
from .wsi_bags import extract_kaiko_slide_features


class PathLabPredictor:
    def __init__(self, model_root: Path) -> None:
        self.model_root = model_root.resolve()
        self.config = json.loads(
            (self.model_root / "model_config.json").read_text(encoding="utf-8")
        )
        artifact = self.model_root / self.config["torchscript"]
        self.artifact_sha256 = sha256(artifact)
        if self.artifact_sha256 != self.config["torchscript_sha256"]:
            raise ValueError("model artifact checksum does not match model_config.json")
        self.model = torch.jit.load(str(artifact), map_location="cpu").eval()
        normalization = self.config.get(
            "normalization",
            {"mean": [0.485, 0.456, 0.406], "std": [0.229, 0.224, 0.225]},
        )
        self.transform = tensor_transform(
            tuple(normalization["mean"]), tuple(normalization["std"])
        )

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
        return _predict_slide_by_tile_aggregation(
            self,
            slide_path,
            output_path=output_path,
            tile_size=tile_size,
            max_tiles=max_tiles,
            minimum_tissue_fraction=minimum_tissue_fraction,
        )


class PathLabMilPredictor:
    """End-to-end inference for an unannotated WSI and a trained MIL head."""

    def __init__(self, model_root: Path) -> None:
        self.model_root = model_root.resolve()
        self.config = json.loads(
            (self.model_root / "model_config.json").read_text(encoding="utf-8")
        )
        artifact = self.model_root / self.config["torchscript"]
        self.artifact_sha256 = sha256(artifact)
        if self.artifact_sha256 != self.config["torchscript_sha256"]:
            raise ValueError("model artifact checksum does not match model_config.json")
        self.selection_provenance = _verify_mil_selection_provenance(
            self.model_root, self.config, self.artifact_sha256
        )
        if self.config.get("encoder") != "kaiko-vits16":
            raise ValueError(
                "MIL inference currently requires the Kaiko ViT-S/16 encoder"
            )
        self.model = torch.jit.load(str(artifact), map_location="cpu").eval()

    def predict_features(
        self,
        features: np.ndarray,
        coordinates: np.ndarray,
        tissue_scores: np.ndarray,
    ) -> dict[str, Any]:
        if features.ndim != 2 or len(features) == 0:
            raise ValueError("MIL features must be a non-empty two-dimensional bag")
        expected_dimension = self.config.get("input_dimension")
        if expected_dimension is not None and features.shape[1] != int(
            expected_dimension
        ):
            raise ValueError(
                "MIL feature dimension mismatch: "
                f"expected {expected_dimension}, found {features.shape[1]}"
            )
        if coordinates.shape != (len(features), 2):
            raise ValueError("MIL coordinates must contain one x/y pair per tile")
        if tissue_scores.shape != (len(features),):
            raise ValueError("MIL tissue scores must contain one value per tile")
        if not all(
            np.isfinite(values).all()
            for values in (features, coordinates, tissue_scores)
        ):
            raise ValueError("MIL inputs contain non-finite values")
        with torch.inference_mode():
            tensor = torch.from_numpy(features.astype(np.float32, copy=False))
            logits, attention = self.model(tensor)
            probabilities = (
                torch.softmax(logits / float(self.config["temperature"]), dim=0)
                .cpu()
                .numpy()
            )
            weights = attention.cpu().numpy()
        prediction = int(probabilities.argmax())
        label = LABELS[prediction]
        with torch.inference_mode():
            class_contributions, _ = self.model.class_evidence(tensor)
            predicted_contributions = class_contributions[:, prediction]
            evidence_strength = torch.softmax(predicted_contributions, dim=0)
            contributions = predicted_contributions.cpu().numpy()
            strengths = evidence_strength.cpu().numpy()
        evidence = [
            {
                "x": int(coordinate[0]),
                "y": int(coordinate[1]),
                "attention": float(weight),
                "tissue_score": float(tissue),
                "predicted_class_contribution": float(contribution),
                "predicted_class_evidence_strength": float(strength),
            }
            for coordinate, weight, tissue, contribution, strength in zip(
                coordinates,
                weights,
                tissue_scores,
                contributions,
                strengths,
                strict=True,
            )
        ]
        evidence.sort(
            key=lambda row: row["predicted_class_evidence_strength"], reverse=True
        )
        for rank, row in enumerate(evidence, start=1):
            row["rank"] = rank
        confidence = float(probabilities[prediction])
        review_threshold = float(self.config["review_threshold"])
        needs_review = confidence < review_threshold
        return {
            "label": label,
            "coarse_group": COARSE_GROUP[label],
            "confidence": confidence,
            "needs_review": needs_review,
            "review": {
                "required": needs_review,
                "confidence_threshold": review_threshold,
                "reason": (
                    "confidence_below_validation_threshold"
                    if needs_review
                    else "validation_threshold_passed"
                ),
                "validation_policy": self.config.get("review_policy"),
            },
            "probabilities": {
                name: float(probabilities[index]) for index, name in enumerate(LABELS)
            },
            "tile_count": len(features),
            "tile_evidence": evidence,
            "evidence_interpretation": (
                "Additive model contributions for the predicted class; these are "
                "not human-verified morphology or causal ground truth."
            ),
            "model": self.config["model_name"],
            "intended_use": self.config["intended_use"],
        }

    def predict_slide(
        self,
        slide_path: Path,
        *,
        output_path: Path,
        target_mpp: float | None = None,
        max_tiles: int | None = None,
        batch_size: int = 16,
    ) -> dict[str, Any]:
        started = time.perf_counter()
        slide_path = slide_path.resolve()
        sampling = self.config.get("sampling", {})
        effective_target_mpp = (
            float(target_mpp)
            if target_mpp is not None
            else float(sampling.get("target_mpp", 0.5))
        )
        effective_max_tiles = (
            int(max_tiles)
            if max_tiles is not None
            else int(sampling.get("maximum_tiles", 128))
        )
        extracted = extract_kaiko_slide_features(
            slide_path,
            target_mpp=effective_target_mpp,
            max_tiles=effective_max_tiles,
            batch_size=batch_size,
        )
        result = self.predict_features(
            extracted.pop("features"),
            extracted.pop("coordinates"),
            extracted.pop("tissue_scores"),
        )
        result.update(
            {
                "schema_version": 1,
                "source": str(slide_path),
                "source_sha256": sha256(slide_path),
                "model_artifact_sha256": self.artifact_sha256,
                "model_config_sha256": sha256(self.model_root / "model_config.json"),
                "encoder_provenance": self.config.get("encoder_provenance"),
                "selection_provenance": self.selection_provenance,
                **extracted,
                "runtime_seconds": time.perf_counter() - started,
            }
        )
        write_json_atomic(output_path, result)
        return result


def _verify_mil_selection_provenance(
    model_root: Path, config: dict[str, Any], artifact_sha256: str
) -> dict[str, Any] | None:
    selection_path = model_root.parent / "selected_model.json"
    if not selection_path.is_file():
        return None
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    if Path(selection["candidate_path"]).resolve() != model_root:
        raise ValueError("selection record points to a different MIL model")
    if selection["artifact_sha256"] != artifact_sha256:
        raise ValueError("selection record MIL checksum mismatch")
    schema_version = int(selection.get("schema_version", 1))
    integrity_verified = False
    if schema_version >= 2:
        required = {
            "model_config_sha256",
            "evaluation_sha256",
            "mean_pool_baselines_sha256",
            "validation_grid_sha256",
        }
        if not required.issubset(selection):
            raise ValueError("v2 selection record is incomplete")
        if selection["model_config_sha256"] != sha256(model_root / "model_config.json"):
            raise ValueError("selection record model-config checksum mismatch")
        if selection["evaluation_sha256"] != sha256(model_root / "evaluation.json"):
            raise ValueError("selection record evaluation checksum mismatch")
        baseline_name = config.get("mean_pool_baselines")
        baseline_sha256 = config.get("mean_pool_baselines_sha256")
        if not baseline_name or not baseline_sha256:
            raise ValueError("MIL model config is missing baseline provenance")
        if (
            selection["mean_pool_baselines_sha256"]
            != config["mean_pool_baselines_sha256"]
        ):
            raise ValueError("selection record baseline checksum mismatch")
        if baseline_sha256 != sha256(model_root / baseline_name):
            raise ValueError("mean-pool baseline artifact checksum mismatch")
        grid_path = model_root.parent / "validation_grid.json"
        if selection["validation_grid_sha256"] != sha256(grid_path):
            raise ValueError("selection record validation-grid checksum mismatch")
        grid = json.loads(grid_path.read_text(encoding="utf-8"))
        if (
            grid.get("selected") != selection.get("candidate")
            or grid.get("advancement_gates") != selection.get("advancement_gates")
            or bool(grid.get("advance_to_locked_test"))
            != bool(selection.get("advance_to_locked_test"))
        ):
            raise ValueError("selection record disagrees with validation grid")
        selected_rows = [
            row
            for row in grid.get("candidates", [])
            if row.get("name") == selection.get("candidate")
        ]
        hash_fields = (
            "artifact_sha256",
            "model_config_sha256",
            "evaluation_sha256",
            "mean_pool_baselines_sha256",
        )
        if len(selected_rows) != 1 or any(
            selected_rows[0].get(field) != selection.get(field) for field in hash_fields
        ):
            raise ValueError("selected artifact hashes disagree with validation grid")
        integrity_verified = True
    return {
        "schema_version": schema_version,
        "record_sha256": sha256(selection_path),
        "integrity_verified": integrity_verified,
        "advance_to_locked_test": bool(selection.get("advance_to_locked_test", False)),
    }


def _tissue_fraction(image: Image.Image) -> float:
    thumbnail = image.copy()
    thumbnail.thumbnail((64, 64), Image.Resampling.BILINEAR)
    pixels = np.asarray(thumbnail, dtype=np.float32) / 255.0
    maximum = pixels.max(axis=2)
    minimum = pixels.min(axis=2)
    saturation = (maximum - minimum) / np.maximum(maximum, 1e-6)
    tissue = (maximum < 0.94) & (saturation > 0.05)
    return float(tissue.mean())


def _predict_slide_by_tile_aggregation(
    predictor: PathLabPredictor,
    slide_path: Path,
    *,
    output_path: Path,
    tile_size: int,
    max_tiles: int,
    minimum_tissue_fraction: float,
) -> dict[str, Any]:
    import tiffslide

    with tiffslide.TiffSlide(str(slide_path)) as slide:
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
            tiles.append(
                {
                    "x": x,
                    "y": y,
                    "width": region.width,
                    "height": region.height,
                    "tissue_fraction": tissue,
                    **predictor.predict_image(region),
                }
            )
    if not tiles:
        raise ValueError("no tissue-containing tiles passed the configured threshold")
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
        "model": predictor.config["model_name"],
        "intended_use": predictor.config["intended_use"],
    }
    write_json_atomic(output_path, result)
    return result
