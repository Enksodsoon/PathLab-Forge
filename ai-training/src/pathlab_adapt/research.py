"""Fail-closed, evidence-bound research primitives for PathLab ADAPT."""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from .io import sha256_file


PRIOR_ART_SOURCES = (
    "pubmed", "pmc", "crossref", "openalex", "arxiv", "ieee-xplore",
    "acm-digital-library", "scopus", "web-of-science", "trial-registries",
    "wipo-patentscope", "google-patents", "product-documentation",
)
SEARCH_STATUSES = frozenset({"searched", "unavailable"})
SCREENING_DECISIONS = frozenset({"include_exact", "include_adjacent", "exclude"})
STUDY_DESIGNS = frozenset({"observational", "randomized", "systematic_review", "methods", "registry", "product_documentation"})
ALLOWED_WORDING = frozenset({"association", "methods", "background", "factual"})
_DOI = re.compile(r"^doi:10\.\d{4,9}/\S+$", re.IGNORECASE)
_PMID = re.compile(r"^pmid:\d+$", re.IGNORECASE)
_CITATION = re.compile(r"\[([A-Za-z][A-Za-z0-9_.:-]*)\]")
_NUMBER = re.compile(r"(?<![A-Za-z])\d+(?:\.\d+)?")
_UNSUPPORTED = re.compile(
    r"\b(?:caus(?:e|ed|es|al|ally|ation)|lead|leads|led|result(?:ed|s)?\s+in|"
    r"effect(?:ive|iveness|s)?|efficacy|improv(?:e|ed|es|ing|ement|ements)|"
    r"benefit(?:ed|s|ting)?|better|superior|positive|higher|lower|first[ -]ever)\b",
    re.IGNORECASE,
)
_WORDING_PATTERNS = {
    "association": re.compile(r"\b(?:association|associated)\b", re.IGNORECASE),
    "methods": re.compile(r"\b(?:method|methods|implemented|implementation)\b", re.IGNORECASE),
    "background": re.compile(r"\b(?:reported|described|documented|background)\b", re.IGNORECASE),
}


def _canonical_hash(payload: object) -> str:
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class ScreeningRecord:
    result_id: str
    decision: str
    reason: str

    def validate(self) -> None:
        if not self.result_id.strip():
            raise ValueError("screening requires a nonblank stable result ID")
        if self.decision not in SCREENING_DECISIONS:
            raise ValueError("screening decision is invalid")
        if not self.reason.strip():
            raise ValueError("screening requires a nonblank reason")


@dataclass(frozen=True)
class NoveltySearch:
    database: str
    query: str
    run_date: str
    status: str
    result_ids: tuple[str, ...] = ()
    reason: str = ""
    screenings: tuple[ScreeningRecord, ...] = ()

    def validate(self) -> None:
        if not self.database.strip() or not self.query.strip() or not self.run_date.strip():
            raise ValueError("novelty search requires database, exact query, and run date")
        if self.status not in SEARCH_STATUSES:
            raise ValueError(f"invalid novelty search status: {self.status}")
        if self.status == "unavailable":
            if not self.reason.strip():
                raise ValueError("unavailable novelty source requires a reason")
            if self.result_ids or self.screenings:
                raise ValueError("unavailable novelty source cannot claim results or screening")
            return
        if not self.result_ids or any(not item.strip() for item in self.result_ids):
            raise ValueError("searched source requires nonblank stable result IDs")
        if len(set(self.result_ids)) != len(self.result_ids):
            raise ValueError("searched source contains duplicate stable result IDs")
        for screening in self.screenings:
            screening.validate()
        screening_ids = [item.result_id for item in self.screenings]
        if Counter(screening_ids) != Counter(self.result_ids):
            raise ValueError("searched source requires exactly one structured screening per result")


@dataclass(frozen=True)
class NoveltyRegistry:
    searches: tuple[NoveltySearch, ...]
    reviewer: str
    signed_at: str = ""

    def freeze(self) -> dict[str, Any]:
        if not self.reviewer.strip() or not self.signed_at.strip():
            raise ValueError("novelty registry requires signed reviewer and timestamp")
        databases = [item.database for item in self.searches]
        if Counter(databases) != Counter(PRIOR_ART_SOURCES):
            raise ValueError("novelty source matrix must contain every exact source once with no unknowns")
        rows: list[dict[str, Any]] = []
        exact_found = False
        searched_count = 0
        for search in self.searches:
            search.validate()
            searched_count += int(search.status == "searched")
            exact_found = exact_found or any(item.decision == "include_exact" for item in search.screenings)
            rows.append(asdict(search))
        status = "exact_prior_art_found" if exact_found else ("complete" if searched_count else "unavailable")
        body = {
            "schema_version": "NoveltyRegistryV1",
            "reviewer": self.reviewer,
            "signed_at": self.signed_at,
            "formal_review_status": status,
            "searches": rows,
        }
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
    claim_text: str = ""
    truth_status: str = "not_applicable"

    def validate(self) -> None:
        if not self.claim_id.strip():
            raise ValueError("evidence requires claim ID")
        if not (_DOI.fullmatch(self.identifier.strip()) or _PMID.fullmatch(self.identifier.strip())):
            raise ValueError("evidence identifier must use strict DOI or PMID syntax")
        if not self.source_location.strip():
            raise ValueError("evidence requires an exact source location or span")
        if self.study_design not in STUDY_DESIGNS:
            raise ValueError("evidence requires an enumerated study design")
        if self.allowed_wording not in ALLOWED_WORDING:
            raise ValueError("evidence requires enumerated allowed wording")
        compatible_designs = {
            "association": {"observational", "randomized", "systematic_review", "registry"},
            "methods": {"methods", "product_documentation"},
            "background": STUDY_DESIGNS,
            "factual": STUDY_DESIGNS,
        }
        if self.study_design not in compatible_designs[self.allowed_wording]:
            raise ValueError("study design and allowed wording are incompatible")
        if self.truth_status not in {"not_applicable", "unverified", "verified_true"}:
            raise ValueError("evidence truth status is invalid")
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


def _snapshot_body(snapshot_id: str, protocol_version: str, model_version: str, pairs: tuple[tuple[str, str], ...]) -> dict[str, Any]:
    return {
        "schema_version": "AnalysisSnapshotV1",
        "snapshot_id": snapshot_id,
        "protocol_version": protocol_version,
        "model_version": model_version,
        "artifact_hashes": dict(pairs),
    }


@dataclass(frozen=True)
class AnalysisSnapshotV1:
    snapshot_id: str
    protocol_version: str
    model_version: str
    artifact_hashes: tuple[tuple[str, str], ...]
    snapshot_sha256: str

    @classmethod
    def freeze(cls, snapshot_id: str, protocol_version: str, model_version: str, artifacts: Mapping[str, Path]) -> "AnalysisSnapshotV1":
        if not snapshot_id.strip() or not protocol_version.strip() or not model_version.strip() or not artifacts:
            raise ValueError("snapshot requires IDs and at least one frozen artifact")
        pairs = tuple(sorted((str(name), sha256_file(Path(path))) for name, path in artifacts.items()))
        body = _snapshot_body(snapshot_id, protocol_version, model_version, pairs)
        return cls(snapshot_id, protocol_version, model_version, pairs, _canonical_hash(body))

    def to_dict(self) -> dict[str, Any]:
        return {**_snapshot_body(self.snapshot_id, self.protocol_version, self.model_version, self.artifact_hashes), "snapshot_sha256": self.snapshot_sha256}

    def verify(self, artifacts: Mapping[str, Path]) -> bool:
        if not self.snapshot_id.strip() or not self.protocol_version.strip() or not self.model_version.strip():
            return False
        names = [name for name, _ in self.artifact_hashes]
        if len(names) != len(set(names)) or names != sorted(names):
            return False
        expected = dict(self.artifact_hashes)
        if set(artifacts) != set(expected):
            return False
        metadata_valid = self.snapshot_sha256 == _canonical_hash(
            _snapshot_body(self.snapshot_id, self.protocol_version, self.model_version, self.artifact_hashes)
        )
        return metadata_valid and all(
            re.fullmatch(r"[0-9a-f]{64}", expected[name]) is not None
            and Path(path).is_file()
            and sha256_file(Path(path)) == expected[name]
            for name, path in artifacts.items()
        )


@dataclass(frozen=True)
class StudyResult:
    status: str
    task_kind: str
    primary_outcome: str
    eligible_pairs: int
    baseline_count: int
    followup_count: int
    matched_followups: int
    unmatched_followups: int
    attrition_count: int
    metrics: dict[str, float | int | None]
    model_fit: None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def _indexed_rows(rows: Sequence[Mapping[str, Any]], label: str) -> dict[tuple[str, str], Mapping[str, Any]]:
    indexed: dict[tuple[str, str], Mapping[str, Any]] = {}
    for row in rows:
        required = {"learner_id", "task_id", "success", "active_minutes", "hints"}
        if not isinstance(row, Mapping) or not required.issubset(row):
            raise ValueError(f"{label} row is missing required fields")
        key = (str(row["learner_id"]), str(row["task_id"]))
        if not all(item.strip() for item in key) or key in indexed:
            raise ValueError(f"{label} rows require unique nonblank learner/task pairs")
        active = row["active_minutes"]
        hints = row["hints"]
        if not isinstance(row["success"], bool):
            raise ValueError(f"{label} row success must be boolean")
        if isinstance(active, bool) or not isinstance(active, (int, float)) or not math.isfinite(float(active)) or float(active) < 0:
            raise ValueError(f"{label} row active_minutes must be finite and nonnegative")
        if isinstance(hints, bool) or not isinstance(hints, int) or hints < 0:
            raise ValueError(f"{label} row hints must be a nonnegative integer")
        indexed[key] = row
    return indexed


def analyze_normal_use(baseline: Sequence[Mapping[str, Any]], followup: Sequence[Mapping[str, Any]], *, task_kind: str = "keyed") -> StudyResult:
    if task_kind not in {"keyed", "spatial"}:
        raise ValueError("task kind must be keyed or spatial")
    baseline_index = _indexed_rows(baseline, "baseline")
    followup_index = _indexed_rows(followup, "follow-up")
    matched_keys = set(baseline_index) & set(followup_index)
    matched = [followup_index[key] for key in sorted(matched_keys)]
    missing = set(baseline_index) - set(followup_index)
    unmatched = set(followup_index) - set(baseline_index)
    primary = "delayed correctness" if task_kind == "keyed" else "success within author-defined coordinate tolerance"
    if matched:
        successes = sum(bool(row["success"]) for row in matched)
        active_minutes = sum(float(row["active_minutes"]) for row in matched)
        metrics: dict[str, float | int | None] = {
            "raw_correctness": successes / len(matched),
            "active_minutes": active_minutes,
            "hint_count": sum(int(row["hints"]) for row in matched),
            "retention_adjusted_efficiency": successes / active_minutes if active_minutes > 0 else None,
        }
    else:
        metrics = {"raw_correctness": None, "active_minutes": None, "hint_count": None, "retention_adjusted_efficiency": None}
    status = "descriptive_only" if baseline_index and not missing and not unmatched else "inconclusive"
    return StudyResult(
        status, task_kind, primary, len(baseline_index), len(baseline), len(followup),
        len(matched), len(unmatched), len(missing), metrics,
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


def _validate_contribution_claim(claim: str, records: list[Mapping[str, Any]]) -> None:
    if _UNSUPPORTED.search(claim):
        raise ValueError("unsupported wording for nonrandomized or inconclusive evidence")
    record_map = {str(row["claim_id"]): row for row in records}
    citations = set(_CITATION.findall(claim))
    if not citations.issubset(record_map):
        raise ValueError("unknown or unfrozen citation in contribution claim")
    for citation in citations:
        row = record_map[citation]
        allowed = str(row["allowed_wording"])
        if allowed == "factual":
            exact = str(row.get("claim_text", "")).strip()
            if not exact or exact.casefold() not in claim.casefold():
                raise ValueError("citation does not use its allowed wording template")
        elif _WORDING_PATTERNS[allowed].search(claim) is None:
            raise ValueError("citation does not use its allowed wording template")
    if _NUMBER.search(_CITATION.sub("", claim)):
        raise ValueError("untraced number in investigator contribution claim")


def _novelty_sentence(novelty: Mapping[str, Any]) -> str:
    required = {"formal_review_status", "reviewer", "signed_at", "sha256", "searches"}
    if not required.issubset(novelty) or not novelty.get("reviewer") or not novelty.get("signed_at"):
        return "Formal prior-art review pending or unavailable."
    body = {key: novelty[key] for key in ("schema_version", "reviewer", "signed_at", "formal_review_status", "searches")}
    if novelty.get("sha256") != _canonical_hash(body):
        return "Formal prior-art review pending or unavailable."
    try:
        searches = tuple(
            NoveltySearch(
                database=str(row["database"]), query=str(row["query"]), run_date=str(row["run_date"]),
                status=str(row["status"]), result_ids=tuple(str(item) for item in row.get("result_ids", ())),
                reason=str(row.get("reason", "")),
                screenings=tuple(ScreeningRecord(**item) for item in row.get("screenings", ())),
            )
            for row in novelty["searches"]
        )
        validated = NoveltyRegistry(searches, str(novelty["reviewer"]), str(novelty["signed_at"])).freeze()
    except (KeyError, TypeError, ValueError):
        return "Formal prior-art review pending or unavailable."
    if validated["sha256"] != novelty.get("sha256") or validated["formal_review_status"] != novelty.get("formal_review_status"):
        return "Formal prior-art review pending or unavailable."
    if novelty["formal_review_status"] == "complete":
        return "To our knowledge, the signed formal review did not locate the reviewed combination."
    if novelty["formal_review_status"] == "exact_prior_art_found":
        return "The formal review located potentially exact prior art; the contribution must be narrowed before publication."
    return "Formal prior-art review pending or unavailable."


def render_manuscript(
    snapshot: AnalysisSnapshotV1,
    evidence: Mapping[str, Any],
    result: StudyResult,
    *,
    novelty: Mapping[str, Any],
    contribution_claim: str | None = None,
) -> str:
    records = evidence.get("records")
    if not isinstance(records, list):
        raise ValueError("evidence registry is not frozen")
    try:
        validated_evidence = EvidenceRegistry(tuple(EvidenceRecord(**row) for row in records)).freeze()
    except (TypeError, ValueError) as error:
        raise ValueError(f"evidence registry failed validation: {error}") from error
    if validated_evidence["sha256"] != evidence.get("sha256"):
        raise ValueError("evidence registry digest is invalid")
    records = validated_evidence["records"]
    contribution = _novelty_sentence(novelty) if contribution_claim is None else contribution_claim
    _validate_contribution_claim(contribution, records)
    metrics = result.metrics
    references = "\n".join(
        f"- [{row['claim_id']}] {row['identifier']} — {row['source_location']} (investigator-verified {row['verified_at']})"
        for row in records
    ) or "- No external claims cited."
    return (
        "# PathLab ADAPT — Evidence-bound draft\n\n## Abstract\n\n"
        f"Status: **{result.status}**. {contribution}\n\n## Introduction\n\n"
        "ADAPT is an educational, non-clinical workflow. It does not analyze slide pixels or generate medical answers.\n\n"
        "## Methods\n\n"
        f"Frozen snapshot: `{snapshot.snapshot_sha256}`. Design: nonrandomized normal-use implementation. "
        "The prespecified model is mixed-effects logistic regression with learner and task intercepts; only adjusted associations may be reported. Primary outcomes are not imputed.\n\n"
        "## Results\n\n"
        f"Primary outcome: {result.primary_outcome}. Eligible pairs: {result.eligible_pairs}. Matched follow-ups: {result.matched_followups}. "
        f"Raw correctness: {metrics['raw_correctness']}; active minutes: {metrics['active_minutes']}; hint count: {metrics['hint_count']}; retention-adjusted efficiency: {metrics['retention_adjusted_efficiency']}. No inferential model fit was produced.\n\n"
        "## Discussion\n\nThe output is descriptive and cannot establish causality or medical-education efficacy.\n\n"
        "## Limitations\n\n"
        f"Attrition count: {result.attrition_count}. Unmatched follow-ups: {result.unmatched_followups}. Missing or unmatched follow-up yields inconclusive status. Device and tier fields are audit covariates only.\n\n"
        f"## References\n\n{references}\n"
    )


@dataclass(frozen=True)
class SourceApproval:
    source_id: str
    relationship: str
    signoff: str
    verified_at: str
    approval_status: str = "pending"

    def validate(self) -> None:
        if not self.source_id.strip() or self.relationship != "unrelated":
            raise ValueError("safe-AI source requires an independently approved unrelated source")
        if not self.signoff.strip() or not self.verified_at.strip() or self.approval_status != "approved":
            raise ValueError("safe-AI source requires signoff and verification time")


def build_safe_ai_literacy_sequence(claim: EvidenceRecord, source: SourceApproval) -> tuple[dict[str, Any], ...]:
    claim.validate()
    source.validate()
    if claim.allowed_wording != "factual" or not claim.claim_text.strip() or claim.truth_status != "verified_true":
        raise ValueError("safe-AI claim must be an evidence-bound signed verified true factual claim")
    return (
        {"order": 1, "kind": "independent_answer", "randomized": False},
        {"order": 2, "kind": "true_claim_unrelated_source", "claim_id": claim.claim_id, "source_id": source.source_id, "randomized": False},
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
