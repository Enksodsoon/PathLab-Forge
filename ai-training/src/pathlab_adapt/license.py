"""Versionable source-license ledger and TRACE distribution boundaries."""

from __future__ import annotations

import hashlib
import re
from dataclasses import asdict, dataclass
from datetime import date
from pathlib import Path
from typing import Any

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def sha256_path(path: Path) -> str:
    """Hash a file or directory tree without exposing host paths in artifacts."""

    if not path.exists():
        raise ValueError(f"checksum artifact does not exist: {path.name}")
    digest = hashlib.sha256()
    if path.is_file():
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
    for item in sorted((item for item in path.rglob("*") if item.is_file()), key=lambda value: value.relative_to(path).as_posix()):
        digest.update(item.relative_to(path).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with item.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        digest.update(b"\0")
    return digest.hexdigest()


@dataclass(frozen=True, slots=True)
class LicenseEntry:
    source_id: str
    source_url: str
    license_name: str
    permitted_use: str
    redistribution: str
    derivative_model_restrictions: str
    retrieval_date: str
    checksum_sha256: str
    research_use_permitted: bool = False
    derivative_models_permitted: bool = False
    data_redistribution_permitted: bool = False
    weights_redistribution_permitted: bool = False

    def __post_init__(self) -> None:
        for field_name in (
            "source_id",
            "source_url",
            "license_name",
            "permitted_use",
            "redistribution",
            "derivative_model_restrictions",
            "retrieval_date",
            "checksum_sha256",
        ):
            if not str(getattr(self, field_name)).strip():
                raise ValueError(f"{field_name} must be non-empty")
        if not self.source_url.startswith(("https://", "http://")):
            raise ValueError("source_url must be an HTTP(S) URL")
        try:
            date.fromisoformat(self.retrieval_date)
        except ValueError as error:
            raise ValueError("retrieval_date must use YYYY-MM-DD") from error
        if not SHA256_PATTERN.fullmatch(self.checksum_sha256):
            raise ValueError("checksum_sha256 must be a lowercase SHA-256 digest")


@dataclass(frozen=True, slots=True)
class LicenseLedger:
    entries: tuple[LicenseEntry, ...]
    schema_version: str = "pathlab-adapt-license-ledger-v1"

    def __post_init__(self) -> None:
        identifiers = [entry.source_id for entry in self.entries]
        if len(identifiers) != len(set(identifiers)):
            raise ValueError("license ledger source_id values must be unique")

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "entries": [asdict(entry) for entry in self.entries],
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> LicenseLedger:
        if payload.get("schema_version") != "pathlab-adapt-license-ledger-v1":
            raise ValueError("unsupported license ledger schema_version")
        entries = payload.get("entries")
        if not isinstance(entries, list):
            raise TypeError("license ledger entries must be a list")
        return cls(tuple(LicenseEntry(**entry) for entry in entries))

    def validate_source(
        self,
        source_id: str,
        artifact: Path,
        *,
        require_derivative_models: bool = False,
    ) -> LicenseEntry:
        matches = [entry for entry in self.entries if entry.source_id == source_id]
        if len(matches) != 1:
            raise ValueError(f"license ledger must contain exactly one {source_id} entry")
        entry = matches[0]
        if not entry.research_use_permitted:
            raise ValueError(f"license does not permit research use for {source_id}")
        if require_derivative_models and not entry.derivative_models_permitted:
            raise ValueError(f"license does not permit derivative models for {source_id}")
        actual = sha256_path(artifact)
        if actual != entry.checksum_sha256:
            raise ValueError(f"license checksum mismatch for {source_id}")
        return entry


@dataclass(frozen=True, slots=True)
class TraceDistribution:
    name: str
    sources: tuple[str, ...]
    license_gated: bool
    redistribute_data: bool
    redistribute_weights: bool
    restrictions: str

    @classmethod
    def trace_open(cls) -> TraceDistribution:
        return cls(
            name="TRACE-Open",
            sources=("oulad", "synthetic"),
            license_gated=False,
            redistribute_data=True,
            redistribute_weights=True,
            restrictions="Preserve source attribution and each source license.",
        )
    @classmethod
    def trace_research(cls) -> TraceDistribution:
        return cls(
            name="TRACE-Research",
            sources=("oulad", "synthetic", "ednet"),
            license_gated=True,
            redistribute_data=False,
            redistribute_weights=False,
            restrictions=(
                "EdNet access and derivative/model terms must be verified locally; "
                "restricted data and weights are never redistributed."
            ),
        )
