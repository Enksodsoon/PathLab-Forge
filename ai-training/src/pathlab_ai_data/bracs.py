"""Fail-closed preparation of the official BRACS whole-slide dataset."""

from __future__ import annotations

import csv
import hashlib
import json
import re
import shutil
import tempfile
from collections import Counter, defaultdict
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

PIPELINE_VERSION = "bracs-clean-v1"
BRACS_SOURCE_URL = "https://www.bracs.icar.cnr.it/"
BRACS_RULES_URL = "https://www.bracs.icar.cnr.it/rules/"

LABELS: Mapping[str, tuple[str, str]] = {
    "N": ("Normal", "BT"),
    "PB": ("Pathological Benign", "BT"),
    "UDH": ("Usual Ductal Hyperplasia", "BT"),
    "FEA": ("Flat Epithelial Atypia", "AT"),
    "ADH": ("Atypical Ductal Hyperplasia", "AT"),
    "DCIS": ("Ductal Carcinoma In Situ", "MT"),
    "IC": ("Invasive Carcinoma", "MT"),
}

OFFICIAL_CLASS_COUNTS: Mapping[str, int] = {
    "N": 44,
    "PB": 147,
    "UDH": 74,
    "FEA": 41,
    "ADH": 48,
    "DCIS": 61,
    "IC": 132,
}
OFFICIAL_SPLIT_COUNTS: Mapping[str, int] = {
    "train": 395,
    "validation": 67,
    "test": 85,
}
OFFICIAL_PATIENT_SPLIT_COUNTS: Mapping[str, int] = {
    "train": 133,
    "validation": 25,
    "test": 31,
}
OFFICIAL_SPLIT_CLASS_COUNTS: Mapping[str, Mapping[str, int]] = {
    "train": {
        "N": 27,
        "PB": 120,
        "UDH": 56,
        "FEA": 24,
        "ADH": 28,
        "DCIS": 40,
        "IC": 100,
    },
    "validation": {
        "N": 10,
        "PB": 11,
        "UDH": 9,
        "FEA": 6,
        "ADH": 8,
        "DCIS": 9,
        "IC": 12,
    },
    "test": {"N": 7, "PB": 16, "UDH": 9, "FEA": 11, "ADH": 12, "DCIS": 12, "IC": 20},
}

_SLIDE_ID = re.compile(r"^BRACS_(\d+)$", re.IGNORECASE)
_TIFF_SIGNATURES = (b"II*\x00", b"MM\x00*", b"II+\x00", b"MM\x00+")
_SUMMARY_ALIASES: Mapping[str, tuple[str, ...]] = {
    "slide_id": ("slideid", "wsiid", "wsi", "filename", "file"),
    "patient_id": ("patientid", "patient", "caseid"),
    "label": ("label", "wsilabel", "subtype", "class"),
    "split": ("referenceset", "split", "set", "partition"),
    "roi_count": ("numberofrois", "roicount", "nrois", "rois"),
}


class PreparationError(RuntimeError):
    """The source data cannot safely produce a train-ready manifest."""


@dataclass(frozen=True)
class BracsPreparationConfig:
    raw_root: Path
    summary_path: Path
    output_root: Path
    expected_slides: int | None = None
    expected_patients: int | None = None
    expected_class_counts: Mapping[str, int] | None = None
    expected_split_counts: Mapping[str, int] | None = None
    expected_patient_split_counts: Mapping[str, int] | None = None
    expected_split_class_counts: Mapping[str, Mapping[str, int]] | None = None
    required_labels: tuple[str, ...] = ()
    min_slide_bytes: int = 1_048_576
    minimum_dimension: int = 1_024
    minimum_tissue_fraction: float = 0.01
    probe_mode: str = "header"


def prepare_bracs(config: BracsPreparationConfig) -> dict[str, Any]:
    """Validate BRACS metadata and WSIs, then atomically write deterministic manifests."""
    raw_root = config.raw_root.resolve()
    summary_path = config.summary_path.resolve()
    output_root = config.output_root.resolve()
    if not raw_root.is_dir():
        raise PreparationError(f"raw root does not exist: {raw_root}")
    if not summary_path.is_file():
        raise PreparationError(f"summary file does not exist: {summary_path}")
    if output_root.exists():
        raise PreparationError(f"output root already exists: {output_root}")
    if _is_within(output_root, raw_root):
        raise PreparationError("output root must be outside the immutable raw dataset")
    if config.probe_mode not in {"header", "tiffslide"}:
        raise PreparationError("probe_mode must be 'header' or 'tiffslide'")

    summary = _read_summary(summary_path)
    discovered = _discover_slides(raw_root)
    missing = sorted(set(summary) - set(discovered), key=_slide_sort_key)
    extra = sorted(set(discovered) - set(summary), key=_slide_sort_key)
    if missing or extra:
        details = []
        if missing:
            details.append("missing WSIs: " + ", ".join(missing[:10]))
        if extra:
            details.append("WSIs absent from summary: " + ", ".join(extra[:10]))
        raise PreparationError("source inventory disagreement; " + "; ".join(details))

    rows: list[dict[str, Any]] = []
    content_hashes: dict[str, str] = {}
    for slide_id in sorted(discovered, key=_slide_sort_key):
        source = discovered[slide_id]
        metadata = summary[slide_id]
        path_facts = _path_facts(source.relative_to(raw_root))
        if path_facts["label"] != metadata["label"]:
            raise PreparationError(
                f"label disagreement for {slide_id}: path={path_facts['label']} "
                f"summary={metadata['label']}"
            )
        if path_facts["split"] != metadata["split"]:
            raise PreparationError(
                f"split disagreement for {slide_id}: path={path_facts['split']} "
                f"summary={metadata['split']}"
            )
        expected_group = LABELS[metadata["label"]][1]
        if path_facts["coarse_group"] != expected_group:
            raise PreparationError(
                f"coarse-group disagreement for {slide_id}: "
                f"path={path_facts['coarse_group']} expected={expected_group}"
            )
        size = source.stat().st_size
        if size < config.min_slide_bytes:
            raise PreparationError(
                f"slide is smaller than {config.min_slide_bytes} bytes: {slide_id}"
            )
        with source.open("rb") as handle:
            signature = handle.read(4)
        if signature not in _TIFF_SIGNATURES:
            raise PreparationError(f"invalid TIFF/SVS signature: {slide_id}")
        digest = _sha256(source)
        previous = content_hashes.get(digest)
        if previous is not None:
            raise PreparationError(
                f"duplicate slide content: {previous} and {slide_id} share {digest}"
            )
        content_hashes[digest] = slide_id
        probe = _probe_slide(source, config)
        relative_path = source.relative_to(raw_root).as_posix()
        rows.append(
            {
                "slide_id": slide_id,
                "patient_id": metadata["patient_id"],
                "label_code": metadata["label"],
                "label_name": LABELS[metadata["label"]][0],
                "coarse_group": expected_group,
                "split": metadata["split"],
                "source_split": metadata["split"],
                "relative_path": relative_path,
                "file_size_bytes": size,
                "sha256": digest,
                "roi_count": metadata["roi_count"],
                "width": probe["width"],
                "height": probe["height"],
                "level_count": probe["level_count"],
                "mpp_x": probe["mpp_x"],
                "mpp_y": probe["mpp_y"],
                "tissue_fraction": probe["tissue_fraction"],
                "integrity_status": probe["integrity_status"],
            }
        )

    _validate_patient_disjoint(rows)
    report = _build_report(rows, config)
    _validate_expected(report, config)
    _write_output_atomically(output_root, rows, report, config.probe_mode)
    return report


def _read_summary(path: Path) -> dict[str, dict[str, Any]]:
    suffix = path.suffix.lower()
    if suffix == ".csv":
        with path.open(newline="", encoding="utf-8-sig") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames is None:
                raise PreparationError("summary CSV has no header")
            raw_rows = list(reader)
            headers = reader.fieldnames
    elif suffix == ".xlsx":
        try:
            from openpyxl import load_workbook
        except ImportError as error:
            raise PreparationError(
                "openpyxl is required to read the BRACS summary XLSX"
            ) from error
        workbook = load_workbook(path, read_only=True, data_only=True)
        sheet = workbook.active
        values = sheet.iter_rows(values_only=True)
        try:
            headers = ["" if value is None else str(value) for value in next(values)]
        except StopIteration as error:
            raise PreparationError("summary XLSX is empty") from error
        raw_rows = [dict(zip(headers, values_row)) for values_row in values]
        workbook.close()
    else:
        raise PreparationError("summary must be a .csv or .xlsx file")

    columns = _resolve_columns(headers)
    result: dict[str, dict[str, Any]] = {}
    for row_number, raw in enumerate(raw_rows, start=2):
        if all(value is None or str(value).strip() == "" for value in raw.values()):
            continue
        slide_id = _normalize_slide_id(raw.get(columns["slide_id"]))
        patient_id = _required_text(
            raw.get(columns["patient_id"]), "patient ID", row_number
        )
        label = _normalize_label(raw.get(columns["label"]), row_number)
        split = _normalize_split(raw.get(columns["split"]), row_number)
        roi_count = _nonnegative_integer(
            raw.get(columns.get("roi_count", "")), row_number
        )
        if slide_id in result:
            raise PreparationError(f"duplicate summary row for {slide_id}")
        result[slide_id] = {
            "patient_id": patient_id,
            "label": label,
            "split": split,
            "roi_count": roi_count,
        }
    if not result:
        raise PreparationError("summary contains no slide records")
    return result


def _resolve_columns(headers: Iterable[str]) -> dict[str, str]:
    normalized = {_header_key(header): header for header in headers}
    resolved: dict[str, str] = {}
    for field, aliases in _SUMMARY_ALIASES.items():
        for alias in aliases:
            if alias in normalized:
                resolved[field] = normalized[alias]
                break
        if field != "roi_count" and field not in resolved:
            raise PreparationError(
                f"summary is missing required {field.replace('_', ' ')} column"
            )
    return resolved


def _discover_slides(raw_root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for path in sorted(raw_root.rglob("*")):
        if not path.is_file() or path.suffix.lower() != ".svs":
            continue
        if path.is_symlink() or not _is_within(path.resolve(), raw_root):
            raise PreparationError(
                f"slide must be a regular file inside raw root: {path}"
            )
        slide_id = _normalize_slide_id(path.name)
        if slide_id in result:
            raise PreparationError(f"duplicate slide ID in raw data: {slide_id}")
        result[slide_id] = path.resolve()
    if not result:
        raise PreparationError("no .svs slides were found below the raw root")
    return result


def _path_facts(relative_path: Path) -> dict[str, str]:
    parts = [part.strip() for part in relative_path.parts[:-1]]
    labels = []
    groups = []
    splits = []
    for part in parts:
        try:
            labels.append(_normalize_label(part, None))
        except PreparationError:
            pass
        group = {
            "bt": "BT",
            "benign": "BT",
            "at": "AT",
            "atypical": "AT",
            "mt": "MT",
            "malignant": "MT",
        }.get(_header_key(part))
        if group is not None:
            groups.append(group)
        try:
            splits.append(_normalize_split(part, None))
        except PreparationError:
            pass
    if len(set(labels)) != 1 or len(set(groups)) != 1 or len(set(splits)) != 1:
        raise PreparationError(
            f"unrecognized BRACS folder structure for {relative_path.as_posix()}"
        )
    return {"label": labels[0], "coarse_group": groups[0], "split": splits[0]}


def _probe_slide(path: Path, config: BracsPreparationConfig) -> dict[str, Any]:
    if config.probe_mode == "header":
        return {
            "width": "",
            "height": "",
            "level_count": "",
            "mpp_x": "",
            "mpp_y": "",
            "tissue_fraction": "",
            "integrity_status": "tiff-header+sha256",
        }
    try:
        import tiffslide
    except ImportError as error:
        raise PreparationError(
            "tiffslide is required for decoded WSI QC; install pathlab-ai-data[wsi]"
        ) from error
    try:
        with tiffslide.TiffSlide(path) as slide:
            width, height = slide.dimensions
            levels = len(slide.level_dimensions)
            if width < config.minimum_dimension or height < config.minimum_dimension:
                raise PreparationError(
                    f"decoded WSI dimensions are too small for {path.name}: {width}x{height}"
                )
            thumbnail = slide.get_thumbnail((512, 512)).convert("RGB")
            pixel_reader = getattr(thumbnail, "get_flattened_data", thumbnail.getdata)
            tissue_fraction = _tissue_fraction(pixel_reader())
            if tissue_fraction < config.minimum_tissue_fraction:
                raise PreparationError(
                    f"decoded WSI has too little tissue for {path.name}: {tissue_fraction:.6f}"
                )
            properties = slide.properties
            mpp_x = _property_float(properties, "tiffslide.mpp-x", "openslide.mpp-x")
            mpp_y = _property_float(properties, "tiffslide.mpp-y", "openslide.mpp-y")
    except PreparationError:
        raise
    except Exception as error:
        raise PreparationError(
            f"decoded WSI validation failed for {path.name}: {error}"
        ) from error
    return {
        "width": width,
        "height": height,
        "level_count": levels,
        "mpp_x": mpp_x,
        "mpp_y": mpp_y,
        "tissue_fraction": round(tissue_fraction, 8),
        "integrity_status": "decoded+tissue-qc+sha256",
    }


def _tissue_fraction(pixels: Iterable[tuple[int, int, int]]) -> float:
    tissue = 0
    total = 0
    for red, green, blue in pixels:
        total += 1
        brightness = (red + green + blue) / 3
        chroma = max(red, green, blue) - min(red, green, blue)
        if brightness < 235 and (chroma > 8 or brightness < 180):
            tissue += 1
    return tissue / total if total else 0.0


def _validate_patient_disjoint(rows: list[dict[str, Any]]) -> None:
    patient_splits: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        patient_splits[row["patient_id"]].add(row["split"])
    leaked = sorted(
        patient for patient, splits in patient_splits.items() if len(splits) > 1
    )
    if leaked:
        raise PreparationError(
            "patient leakage across official splits: " + ", ".join(leaked[:10])
        )


def _build_report(
    rows: list[dict[str, Any]], config: BracsPreparationConfig
) -> dict[str, Any]:
    class_counts = Counter(row["label_code"] for row in rows)
    split_counts = Counter(row["split"] for row in rows)
    split_class_counts: dict[str, Counter[str]] = defaultdict(Counter)
    patient_splits: dict[str, str] = {}
    for row in rows:
        split_class_counts[row["split"]][row["label_code"]] += 1
        patient_splits[row["patient_id"]] = row["split"]
    patient_split_counts = Counter(patient_splits.values())
    return {
        "pipeline_version": PIPELINE_VERSION,
        "validation_level": (
            "decoded-wsi-tissue-qc"
            if config.probe_mode == "tiffslide"
            else "header-only-test-mode"
        ),
        "clean_slide_count": len(rows),
        "rejected_slide_count": 0,
        "patient_count": len(patient_splits),
        "class_counts": {
            label: class_counts[label] for label in LABELS if class_counts[label]
        },
        "split_counts": {
            split: split_counts[split]
            for split in ("train", "validation", "test")
            if split_counts[split]
        },
        "patient_split_counts": {
            split: patient_split_counts[split]
            for split in ("train", "validation", "test")
            if patient_split_counts[split]
        },
        "split_class_counts": {
            split: {
                label: split_class_counts[split][label]
                for label in LABELS
                if split_class_counts[split][label]
            }
            for split in ("train", "validation", "test")
            if split_counts[split]
        },
    }


def _validate_expected(
    report: Mapping[str, Any], config: BracsPreparationConfig
) -> None:
    if (
        config.expected_slides is not None
        and report["clean_slide_count"] != config.expected_slides
    ):
        raise PreparationError(
            f"expected {config.expected_slides} slides, found {report['clean_slide_count']}"
        )
    if (
        config.expected_patients is not None
        and report["patient_count"] != config.expected_patients
    ):
        raise PreparationError(
            f"expected {config.expected_patients} patients, found {report['patient_count']}"
        )
    missing_labels = [
        label for label in config.required_labels if label not in report["class_counts"]
    ]
    if missing_labels:
        raise PreparationError(
            "required labels are absent: " + ", ".join(missing_labels)
        )
    _require_counts("class", report["class_counts"], config.expected_class_counts)
    _require_counts("split", report["split_counts"], config.expected_split_counts)
    _require_counts(
        "patient split",
        report["patient_split_counts"],
        config.expected_patient_split_counts,
    )
    if config.expected_split_class_counts is not None:
        actual = report["split_class_counts"]
        if actual != _plain_nested_counts(config.expected_split_class_counts):
            raise PreparationError(
                "split/class counts disagree with the published BRACS benchmark: "
                f"expected={_plain_nested_counts(config.expected_split_class_counts)} actual={actual}"
            )


def _write_output_atomically(
    output_root: Path,
    rows: list[dict[str, Any]],
    report: dict[str, Any],
    probe_mode: str,
) -> None:
    output_root.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(
        tempfile.mkdtemp(prefix=f"{output_root.name}.partial-", dir=output_root.parent)
    )
    try:
        fieldnames = [
            "slide_id",
            "patient_id",
            "label_code",
            "label_name",
            "coarse_group",
            "split",
            "source_split",
            "relative_path",
            "file_size_bytes",
            "sha256",
            "roi_count",
            "width",
            "height",
            "level_count",
            "mpp_x",
            "mpp_y",
            "tissue_fraction",
            "integrity_status",
        ]
        _write_csv(staging / "manifest.csv", rows, fieldnames)
        split_root = staging / "splits"
        split_root.mkdir()
        for split in ("train", "validation", "test"):
            split_rows = [row for row in rows if row["split"] == split]
            _write_csv(split_root / f"{split}.csv", split_rows, fieldnames)
        checksums = "".join(
            f"{row['sha256']}  {row['relative_path']}\n" for row in rows
        )
        (staging / "checksums.sha256").write_text(
            checksums, encoding="utf-8", newline="\n"
        )
        report = dict(report)
        report.update(
            {
                "dataset": "BRACS",
                "source_url": BRACS_SOURCE_URL,
                "rules_url": BRACS_RULES_URL,
                "license_declared_by_source": "CC0-1.0 after named registration and acceptance of source rules",
                "manifest_sha256": _sha256(staging / "manifest.csv"),
                "raw_data_copied": False,
                "pixel_or_roi_annotations_used_for_training_manifest": False,
                "probe_mode": probe_mode,
            }
        )
        (staging / "dataset_manifest.json").write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        (staging / "DATASET_CARD.md").write_text(
            _dataset_card(report), encoding="utf-8", newline="\n"
        )
        staging.replace(output_root)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def _write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def _dataset_card(report: Mapping[str, Any]) -> str:
    return f"""# BRACS train-ready manifest

- Pipeline: `{report["pipeline_version"]}`
- Source: {BRACS_SOURCE_URL}
- Source rules: {BRACS_RULES_URL}
- Clean WSIs: {report["clean_slide_count"]}
- Patients: {report["patient_count"]}
- Validation: `{report["validation_level"]}`

The raw BRACS files are not copied or redistributed. Paths remain relative to the
authorized local raw-data root. The official patient-disjoint reference splits are
preserved and verified. ROI and pixel annotations are not imported into the training
manifest; they remain reserved for later held-out evidence-localization evaluation.
"""


def _normalize_slide_id(value: Any) -> str:
    text = _required_text(value, "slide ID", None)
    if text.lower().endswith(".svs"):
        text = text[:-4]
    match = _SLIDE_ID.fullmatch(text.strip())
    if match is None:
        raise PreparationError(f"invalid BRACS slide ID: {value!r}")
    return f"BRACS_{match.group(1)}"


def _normalize_label(value: Any, row_number: int | None) -> str:
    text = _required_text(value, "label", row_number)
    key = _header_key(text)
    aliases = {
        "n": "N",
        "normal": "N",
        "normaltissue": "N",
        "pb": "PB",
        "pathologicalbenign": "PB",
        "pathologicbenign": "PB",
        "udh": "UDH",
        "usualductalhyperplasia": "UDH",
        "fea": "FEA",
        "flatepithelialatypia": "FEA",
        "adh": "ADH",
        "atypicalductalhyperplasia": "ADH",
        "dcis": "DCIS",
        "ductalcarcinomainsitu": "DCIS",
        "ic": "IC",
        "invasivecarcinoma": "IC",
    }
    if key not in aliases:
        raise PreparationError(f"unknown BRACS label at row {row_number}: {value!r}")
    return aliases[key]


def _normalize_split(value: Any, row_number: int | None) -> str:
    text = _required_text(value, "reference split", row_number)
    key = _header_key(text)
    aliases = {
        "train": "train",
        "training": "train",
        "validation": "validation",
        "valid": "validation",
        "val": "validation",
        "test": "test",
        "testing": "test",
    }
    if key not in aliases:
        raise PreparationError(
            f"unknown reference split at row {row_number}: {value!r}"
        )
    return aliases[key]


def _nonnegative_integer(value: Any, row_number: int) -> int:
    if value is None or str(value).strip() == "":
        return 0
    try:
        number = int(float(str(value).strip()))
    except ValueError as error:
        raise PreparationError(
            f"invalid ROI count at row {row_number}: {value!r}"
        ) from error
    if number < 0:
        raise PreparationError(f"negative ROI count at row {row_number}")
    return number


def _required_text(value: Any, name: str, row_number: int | None) -> str:
    text = "" if value is None else str(value).strip()
    if not text:
        location = f" at row {row_number}" if row_number is not None else ""
        raise PreparationError(f"missing {name}{location}")
    return text


def _header_key(value: Any) -> str:
    return re.sub(r"[^a-z0-9]", "", str(value).strip().lower())


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _slide_sort_key(slide_id: str) -> tuple[int, str]:
    match = _SLIDE_ID.fullmatch(slide_id)
    return (int(match.group(1)), slide_id) if match else (2**63 - 1, slide_id)


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _property_float(properties: Mapping[str, Any], *names: str) -> float | str:
    for name in names:
        value = properties.get(name)
        if value is not None and str(value).strip():
            try:
                return float(value)
            except (TypeError, ValueError):
                pass
    return ""


def _require_counts(
    name: str,
    actual: Mapping[str, int],
    expected: Mapping[str, int] | None,
) -> None:
    if expected is not None and dict(actual) != dict(expected):
        raise PreparationError(
            f"{name} counts disagree with the published BRACS benchmark: "
            f"expected={dict(expected)} actual={dict(actual)}"
        )


def _plain_nested_counts(
    value: Mapping[str, Mapping[str, int]],
) -> dict[str, dict[str, int]]:
    return {split: dict(counts) for split, counts in value.items()}
