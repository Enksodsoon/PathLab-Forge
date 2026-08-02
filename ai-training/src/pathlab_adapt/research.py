"""Evidence-bound, offline research and manuscript primitives for ADAPT.

This module deliberately performs no literature search, medical inference, or
statistical model fitting. It freezes investigator-supplied evidence and emits
only design-compatible descriptive outputs.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from .io import sha256_file


SEARCH_STATUSES = frozenset({"searched", "unavailable"})
PRIOR_ART_SOURCES = (
    "pubmed", "pmc", "crossref", "openalex", "arxiv", "ieee-xplore",
    "acm-digital-library", "scopus", "web-of-science", "trial-registries",
    "wipo-patentscope", "google-patents", "product-documentation",
)
_UNSUPPORTED_WORDING = re.compile(
    r"\b(caus(?:e|ed|al)|effective(?:ness)?|efficacy|improv(?:e|ed|ement)|"
    r"benefit(?:ed|s)?|superior|first[ -]ever)\b",
    re.IGNORECASE,
)
_CITATION = re.compile(r"\[([A-Za-z][A-Za-z0-9_.:-]*)\]")
_NUMBER = re.compile(r"(?<![A-Za-z])\d+(?:\.\d+)?")


def _canonical_hash(payload: object) -> str:
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class NoveltySearch:
    database: str
    query: str
    run_date: str
    status: str
    result_ids: tuple[str, ...] = ()
    reason: str = ""

    def validate(self) -> None:
        if not self.database.strip() or not self.query.strip() or not self.run_date.strip():
            raise ValueError("novelty search requires database, query, and run date")
        if self.status not in SEARCH_STATUSES:
            raise ValueError(f"invalid novelty search status: {self.status}")
        if self.status == "unavailable" and not self.reason.strip():
            raise ValueError("unavailable novelty source requires a reason")
        if self.status == "unavailable" and self.result_ids:
            raise ValueError("unavailable novelty source cannot claim result IDs")
        if self.status == "searched" and (not self.result_ids or "screen" not in self.reason.lower()):
            raise ValueError("searched novelty source requires result IDs and a screening decision")


@dataclass(frozen=True)
class NoveltyRegistry:
    searches: tuple[NoveltySearch, ...]
    reviewer: str

    def freeze(self) -> dict[str, Any]:
        if not self.reviewer.strip():
            raise ValueError("novelty registry requires a reviewer")
        if not self.searches:
            raise ValueError("novelty registry cannot be empty")
        seen: set[str] = set()
        rows: list[dict[str, Any]] = []
        for search in self.searches:
            search.validate()
            key = search.database.casefold()
            if key in seen:
                raise ValueError(f"duplicate novelty database: {search.database}")
            seen.add(key)
            rows.append(asdict(search))
        body = {"schema_version": "NoveltyRegistryV1", "reviewer": self.reviewer, "searches": rows}
        return {**body, "sha256": _canonical_hash(body)}


@dataclass(frozen=True)
class EvidenceRecord:
    claim_id: str
    identifier: str
    source_location: str
    study_design: str
    allowed_wording: str
    signoff: str
    verified_at: str

    def validate(self) -> None:
        if not self.claim_id.strip():
            raise ValueError("evidence requires claim ID")
        identifier = self.identifier.strip().lower()
        if not (identifier.startswith("doi:10.") or identifier.startswith("pmid:")):
            raise ValueError("evidence identifier must be a DOI or PMID")
        if not self.source_location.strip():
            raise ValueError("evidence requires an exact source location or span")
        if not self.signoff.strip() or not self.verified_at.strip():
            raise ValueError("evidence requires investigator signoff and verification timestamp")


@dataclass(frozen=True)
class EvidenceRegistry:
    records: tuple[EvidenceRecord, ...]

    def freeze(self) -> dict[str, Any]:
        seen: set[str] = set()
        rows: list[dict[str, str]] = []
        for record in self.records:
            record.validate()
            if record.claim_id in seen:
                raise ValueError(f"duplicate evidence claim: {record.claim_id}")
            seen.add(record.claim_id)
            rows.append(asdict(record))
        body = {"schema_version": "EvidenceRegistryV1", "records": rows}
        return {**body, "sha256": _canonical_hash(body)}


@dataclass(frozen=True)
class AnalysisSnapshotV1:
    snapshot_id: str
    protocol_version: str
    model_version: str
    artifact_hashes: tuple[tuple[str, str], ...]
    snapshot_sha256: str

    @classmethod
    def freeze(
        cls,
        snapshot_id: str,
        protocol_version: str,
        model_version: str,
        artifacts: Mapping[str, Path],
    ) -> "AnalysisSnapshotV1":
        if not snapshot_id or not protocol_version or not model_version or not artifacts:
            raise ValueError("snapshot requires IDs and at least one frozen artifact")
        pairs = tuple(sorted((name, sha256_file(Path(path))) for name, path in artifacts.items()))
        body = {
            "schema_version": "AnalysisSnapshotV1",
            "snapshot_id": snapshot_id,
            "protocol_version": protocol_version,
            "model_version": model_version,
            "artifact_hashes": dict(pairs),
        }
        return cls(snapshot_id, protocol_version, model_version, pairs, _canonical_hash(body))

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": "AnalysisSnapshotV1",
            "snapshot_id": self.snapshot_id,
            "protocol_version": self.protocol_version,
            "model_version": self.model_version,
            "artifact_hashes": dict(self.artifact_hashes),
            "snapshot_sha256": self.snapshot_sha256,
        }

    def verify(self, artifacts: Mapping[str, Path]) -> bool:
        expected = dict(self.artifact_hashes)
        if set(artifacts) != set(expected):
            return False
        return all(Path(path).is_file() and sha256_file(Path(path)) == expected[name] for name, path in artifacts.items())


@dataclass(frozen=True)
class StudyResult:
    status: str
    task_kind: str
    primary_outcome: str
    baseline_count: int
    followup_count: int
    matched_followups: int
    attrition_count: int
    metrics: dict[str, float | int | None]
    model_fit: None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def analyze_normal_use(
    baseline: Sequence[Mapping[str, Any]],
    followup: Sequence[Mapping[str, Any]],
    *,
    task_kind: str = "keyed",
) -> StudyResult:
    if task_kind not in {"keyed", "spatial"}:
        raise ValueError("task kind must be keyed or spatial")
    baseline_keys = {(str(row["learner_id"]), str(row["task_id"])) for row in baseline}
    matched = [row for row in followup if (str(row["learner_id"]), str(row["task_id"])) in baseline_keys]
    primary = "delayed correctness" if task_kind == "keyed" else "success within author-defined coordinate tolerance"
    if not matched:
        metrics: dict[str, float | int | None] = {
            "raw_correctness": None,
            "active_minutes": None,
            "hint_count": None,
            "retention_adjusted_efficiency": None,
        }
        status = "inconclusive"
    else:
        successes = sum(bool(row["success"]) for row in matched)
        active_minutes = sum(float(row["active_minutes"]) for row in matched)
        metrics = {
            "raw_correctness": successes / len(matched),
            "active_minutes": active_minutes,
            "hint_count": sum(int(row["hints"]) for row in matched),
            "retention_adjusted_efficiency": successes / active_minutes if active_minutes > 0 else None,
        }
        status = "descriptive_only"
    return StudyResult(
        status=status,
        task_kind=task_kind,
        primary_outcome=primary,
        baseline_count=len(baseline),
        followup_count=len(followup),
        matched_followups=len(matched),
        attrition_count=max(0, len(baseline) - len(matched)),
        metrics=metrics,
    )


@dataclass(frozen=True)
class StudyProtocol:
    protocol_version: str
    design: str
    analysis_plan: str
    withdrawal_policy: str

    @classmethod
    def default(cls) -> "StudyProtocol":
        return cls(
            "StudyProtocolV1",
            "approved nonrandomized normal-use pre/post implementation",
            "Mixed-effects logistic model planned for delayed correctness with learner and task intercepts; adjusted associations only; no primary-outcome imputation; report attrition and inverse-probability sensitivity. No fit is emitted without approved data and dependencies.",
            "Honor withdrawal according to the frozen institutional protocol and exclude withdrawn records from new snapshots.",
        )


def _validate_contribution_claim(claim: str, known_claim_ids: set[str]) -> None:
    if _UNSUPPORTED_WORDING.search(claim):
        raise ValueError("unsupported wording for nonrandomized or inconclusive evidence")
    citations = set(_CITATION.findall(claim))
    if not citations.issubset(known_claim_ids):
        raise ValueError("unknown or unfrozen citation in contribution claim")
    without_citations = _CITATION.sub("", claim)
    if _NUMBER.search(without_citations):
        raise ValueError("untraced number in investigator contribution claim")


def render_manuscript(
    snapshot: AnalysisSnapshotV1,
    evidence: Mapping[str, Any],
    result: StudyResult,
    *,
    contribution_claim: str = "To our knowledge, the reviewed combination was not located.",
) -> str:
    records = evidence.get("records")
    if not isinstance(records, list):
        raise ValueError("evidence registry is not frozen")
    known = {str(row["claim_id"]) for row in records}
    _validate_contribution_claim(contribution_claim, known)
    metrics = result.metrics
    metric_text = (
        f"Raw correctness: {metrics['raw_correctness']}; active minutes: {metrics['active_minutes']}; "
        f"hint count: {metrics['hint_count']}; retention-adjusted efficiency: "
        f"{metrics['retention_adjusted_efficiency']}."
    )
    references = "\n".join(
        f"- [{row['claim_id']}] {row['identifier']} — {row['source_location']} (investigator-verified {row['verified_at']})"
        for row in records
    ) or "- No external claims cited."
    return (
        "# PathLab ADAPT — Evidence-bound draft\n\n"
        "## Abstract\n\n"
        f"Status: **{result.status}**. {contribution_claim}\n\n"
        "## Introduction\n\n"
        "ADAPT is an educational, non-clinical workflow. It does not analyze slide pixels or generate medical answers.\n\n"
        "## Methods\n\n"
        f"Frozen snapshot: `{snapshot.snapshot_sha256}`. Design: nonrandomized normal-use implementation. "
        "The prespecified model is mixed-effects logistic regression with learner and task intercepts; only adjusted associations may be reported. Primary outcomes are not imputed.\n\n"
        "## Results\n\n"
        f"Primary outcome: {result.primary_outcome}. Matched follow-ups: {result.matched_followups}. {metric_text} "
        "No inferential model fit was produced.\n\n"
        "## Discussion\n\n"
        "The output is descriptive and cannot establish causality or medical-education efficacy.\n\n"
        "## Limitations\n\n"
        f"Attrition count: {result.attrition_count}. Missing or unmatched follow-up yields inconclusive status. "
        "Device and tier fields are audit covariates only.\n\n"
        "## References\n\n"
        f"{references}\n"
    )


def build_safe_ai_literacy_sequence(claim_id: str, source_id: str) -> tuple[dict[str, Any], ...]:
    if not claim_id or not source_id:
        raise ValueError("safe-AI literacy sequence requires approved claim and source IDs")
    return (
        {"order": 1, "kind": "independent_answer", "randomized": False},
        {"order": 2, "kind": "true_claim_unrelated_source", "claim_id": claim_id, "source_id": source_id, "randomized": False},
        {"order": 3, "kind": "source_check", "randomized": False},
        {"order": 4, "kind": "immediate_debrief", "randomized": False},
    )


def render_institutional_dossier(protocol: StudyProtocol) -> str:
    return f"""# PathLab ADAPT institutional approval dossier

The local institutional authority determines applicable review and approval. Codex does not determine approval.

## Intended use
Faculty-controlled educational research using keyed or spatial tasks and pseudonymous telemetry.

## Prohibited use
Clinical diagnosis, treatment, slide-pixel inference, medical-answer generation, anonymous research access, randomization, deception, and causal claims.

## Protocol and Consent
{protocol.design}. Consent is version-bound and precedes collection.

## Withdrawal
{protocol.withdrawal_policy}

## Privacy and security
Identity mapping remains outside PathLab; learner tokens expire; answer keys and raw chat are not disclosed.

## Accessibility
Keyboard, touch, stylus, screen-reader, responsive layouts, and WCAG 2.2 AA verification are required.

## Model and data cards
Frozen versions, licenses, provenance, calibration, OOD state, failed gates, and prohibited uses are disclosed.

## Coach provider controls
No identity, pixels, event histories, keys, or PHI leave PathLab. Quota or safety failure uses bounded local templates.

## Fallback and Rollback
Any uncertainty, OOD, runtime, approval, or provider failure returns fixed-order delivery. Released weights never update online.

## Analysis and Manuscript plan
{protocol.analysis_plan} Outputs are evidence-bound; no automatic submission occurs.
"""
