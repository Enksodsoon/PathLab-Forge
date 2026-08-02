"""Content-addressed, immutable local storage for TRACE-SIM artifacts."""

from __future__ import annotations

import json
import os
import tempfile
from dataclasses import asdict
from pathlib import Path
from typing import Iterable

from .io import sha256_file, write_json_atomic
from .ontology import LearnerEvent


def default_artifact_root() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    return base / "PathLab Forge" / "adapt" / "trace-sim"


def write_parquet_dataset(
    events: Iterable[LearnerEvent], *, root: Path | None = None, shard_events: int = 64_000,
    metadata: dict[str, object] | None = None,
) -> dict[str, object]:
    if shard_events < 1:
        raise ValueError("shard_events must be positive")
    try:
        import pyarrow as pa
        import pyarrow.parquet as pq
    except ImportError as error:
        raise RuntimeError("Parquet storage requires pathlab-ai-data[adapt-train]") from error
    store = (root or default_artifact_root()).resolve()
    store.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="trace-sim-stage-", dir=store) as stage_name:
        stage = Path(stage_name)
        shards: list[dict[str, object]] = []
        batch: list[dict[str, object]] = []
        total = 0
        for event in events:
            row = asdict(event)
            row["metadata"] = json.dumps(row["metadata"], sort_keys=True, separators=(",", ":"))
            batch.append(row)
            if len(batch) >= shard_events:
                shards.append(_write_shard(pa, pq, stage, batch, len(shards)))
                total += len(batch); batch = []
        if batch:
            shards.append(_write_shard(pa, pq, stage, batch, len(shards))); total += len(batch)
        if total == 0:
            raise ValueError("dataset is empty")
        identity = {
            "schema": "pathlab.trace-sim-dataset/2", "event_count": total,
            "shards": [{"file": item["file"], "sha256": item["sha256"], "rows": item["rows"], "bytes": item["bytes"]} for item in shards],
            "metadata": metadata or {}, "scope": "synthetic_software_recovery_only",
        }
        import hashlib
        digest = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        destination = store / digest
        if destination.exists():
            existing = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
            if existing.get("content_sha256") != digest:
                raise RuntimeError("content-address collision or tampered artifact")
            return existing
        manifest = {**identity, "content_sha256": digest, "path": str(destination), "immutable": True}
        write_json_atomic(stage / "manifest.json", manifest)
        stage.rename(destination)
        return manifest


def _write_shard(pa: object, pq: object, stage: Path, rows: list[dict[str, object]], index: int) -> dict[str, object]:
    path = stage / f"events-{index:05d}.parquet"
    table = pa.Table.from_pylist(rows)
    pq.write_table(table, path, compression="zstd", use_dictionary=True)
    return {"file": path.name, "rows": len(rows), "sha256": sha256_file(path), "bytes": path.stat().st_size}


def verify_dataset_manifest(manifest_path: Path) -> dict[str, object]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    root = manifest_path.parent
    for shard in manifest.get("shards", []):
        path = root / str(shard["file"])
        if not path.is_file() or sha256_file(path) != shard["sha256"]:
            raise ValueError(f"artifact shard failed verification: {path.name}")
    return manifest
