"""Versionable source-license ledger and TRACE distribution boundaries."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from datetime import date
from typing import Any

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


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


@dataclass(frozen=True, slots=True)
class TraceDistribution:
    name: str
    sources: tuple[str, ...]
    license_gated: bool
    redistribute_data: bool
    redistribute_weights: bool
    restrictions: str

    @classmethod
    def trace_open(cls) -> "TraceDistribution":
        return cls(
            name="TRACE-Open",
            sources=("oulad", "synthetic"),
            license_gated=False,
            redistribute_data=True,
            redistribute_weights=True,
            restrictions="Preserve source attribution and each source license.",
        )
    @classmethod
    def trace_research(cls) -> "TraceDistribution":
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
