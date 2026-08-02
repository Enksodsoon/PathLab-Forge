"""Fail-closed, evidence-bound research primitives for PathLab ADAPT."""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from .io import sha256_file

PRIOR_ART_SOURCES = (
    "pubmed", "pmc", "crossref", "openalex", "arxiv", "ieee-xplore",
    "acm-digital-library", "scopus", "web-of-science", "trial-registries",
    "wipo-patentscope", "google-patents", "product-documentation",
)
SEARCH_STATUSES = frozenset({"searched", "unavailable"})
SCREENING_DECISIONS = frozenset({"include_exact", "include_adjacent", "exclude"})
STUDY_DESIGNS = frozenset({"observational", "randomized", "systematic_review", "methods", "registry", "product_documentation"})
ALLOWED_WORDING = frozenset(
    {"association", "methods", "background", "factual", "approved_outcome"}
)
CLAIM_KINDS = frozenset(
    {"association", "methods", "background", "safe_ai_fact", "approved_outcome"}
)
MANUSCRIPT_CLAIM_TEMPLATES = {
    "association": "An association was reported.",
    "methods": "A methods description was registered.",
    "background": "Background source material was registered.",
}
_DOI = re.compile(r"^doi:10\.\d{4,9}/\S+$", re.IGNORECASE)
_PMID = re.compile(r"^pmid:\d+$", re.IGNORECASE)
_CITATION = re.compile(r"\[([A-Za-z][A-Za-z0-9_.:-]*)\]")
_NUMBER = re.compile(r"(?<![A-Za-z])\d+(?:\.\d+)?")
_SAFE_TOKEN = re.compile(r"^[A-Za-z][A-Za-z0-9_.:-]{0,127}$")
_SAFE_SOURCE_LOCATION = re.compile(
    r"^[A-Za-z0-9§][A-Za-z0-9 .,:;#/_()§-]{0,255}$"
)
_UTC_TIMESTAMP = re.compile(
    r"^\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])T"
    r"(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\dZ$"
)


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
        if exact_found:
            status = "exact_prior_art_found"
        elif searched_count == len(self.searches):
            status = "complete"
        elif searched_count == 0:
            status = "unavailable"
        else:
            status = "partial_unavailable"
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
    content_sha256: str = ""
    claim_kind: str = ""

    def resolved_claim_kind(self) -> str:
        legacy_kind = "safe_ai_fact" if self.allowed_wording == "factual" else self.allowed_wording
        if self.claim_kind and self.claim_kind != legacy_kind:
            raise ValueError("claim kind and legacy allowed wording are incompatible")
        return self.claim_kind or legacy_kind

    def validate(self) -> None:
        if _SAFE_TOKEN.fullmatch(self.claim_id) is None:
            raise ValueError("evidence claim ID must be a safe token")
        if not (_DOI.fullmatch(self.identifier.strip()) or _PMID.fullmatch(self.identifier.strip())):
            raise ValueError("evidence identifier must use strict DOI or PMID syntax")
        if _SAFE_SOURCE_LOCATION.fullmatch(self.source_location) is None:
            raise ValueError(
                "evidence source location must be bounded safe single-line text"
            )
        if self.study_design not in STUDY_DESIGNS:
            raise ValueError("evidence requires an enumerated study design")
        if self.allowed_wording not in ALLOWED_WORDING:
            raise ValueError("evidence requires enumerated allowed wording")
        claim_kind = self.resolved_claim_kind()
        if claim_kind not in CLAIM_KINDS:
            raise ValueError("evidence requires an enumerated claim kind")
        compatible_designs = {
            "association": {"observational", "randomized", "systematic_review", "registry"},
            "methods": {"methods", "product_documentation"},
            "background": STUDY_DESIGNS,
            "safe_ai_fact": STUDY_DESIGNS,
            "approved_outcome": {"observational", "randomized", "systematic_review"},
        }
        if self.study_design not in compatible_designs[claim_kind]:
            raise ValueError("study design and allowed wording are incompatible")
        if self.truth_status not in {"not_applicable", "unverified", "verified_true"}:
            raise ValueError("evidence truth status is invalid")
        if _SAFE_TOKEN.fullmatch(self.signoff) is None:
            raise ValueError("evidence investigator signoff must be a safe token")
        if _UTC_TIMESTAMP.fullmatch(self.verified_at) is None:
            raise ValueError("evidence verification timestamp must be strict UTC ISO-8601")
        try:
            datetime.strptime(self.verified_at, "%Y-%m-%dT%H:%M:%S%z")
        except ValueError as exc:
            raise ValueError(
                "evidence verification timestamp must be a real UTC ISO-8601 instant"
            ) from exc
        if not self.claim_text.strip():
            raise ValueError("evidence used by the manuscript requires exact signed claim text")
        if _CITATION.search(self.claim_text):
            raise ValueError("signed claim text cannot contain citation markup; the engine binds citations locally")
        if re.fullmatch(r"[0-9a-f]{64}", self.content_sha256) is None:
            raise ValueError("evidence requires a lowercase SHA-256 source content hash")


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
            row = asdict(record)
            row["claim_kind"] = record.resolved_claim_kind()
            rows.append(row)
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
    def freeze(cls, snapshot_id: str, protocol_version: str, model_version: str, artifacts: Mapping[str, Path]) -> AnalysisSnapshotV1:
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
            raise TypeError(f"{label} row success must be boolean")
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
    def default(cls) -> StudyProtocol:
        return cls(
            "StudyProtocolV1",
            "approved nonrandomized normal-use pre/post implementation",
            "Mixed-effects logistic model planned for delayed correctness with learner and task intercepts; adjusted associations only; no primary-outcome imputation; report attrition and inverse-probability sensitivity. No fit is emitted without approved data and dependencies.",
            "Honor withdrawal according to the frozen institutional protocol and exclude withdrawn records from new snapshots.",
        )


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
) -> str:
    records = evidence.get("records")
    if not isinstance(records, list):
        raise TypeError("evidence registry is not frozen")
    try:
        validated_evidence = EvidenceRegistry(tuple(EvidenceRecord(**row) for row in records)).freeze()
    except (TypeError, ValueError) as error:
        raise ValueError(f"evidence registry failed validation: {error}") from error
    if validated_evidence["sha256"] != evidence.get("sha256"):
        raise ValueError("evidence registry digest is invalid")
    records = validated_evidence["records"]
    contribution = _novelty_sentence(novelty)
    evidence_claims: list[str] = []
    manuscript_records: list[Mapping[str, Any]] = []
    for row in records:
        claim_kind = str(row["claim_kind"])
        if claim_kind == "approved_outcome":
            raise ValueError(
                "approved outcome claims require a frozen approved matched analysis/result artifact"
            )
        if claim_kind == "safe_ai_fact":
            continue
        claim_text = str(row["claim_text"]).strip()
        template = MANUSCRIPT_CLAIM_TEMPLATES[claim_kind]
        if claim_text != template:
            raise ValueError(
                "manuscript evidence must use its exact structured claim template"
            )
        evidence_claims.append(f"{template} [{row['claim_id']}]")
        manuscript_records.append(row)
    prior_evidence = "\n".join(f"- {claim}" for claim in evidence_claims) or "- No signed external evidence claims were supplied."
    metrics = result.metrics
    references = "\n".join(
        f"- [{row['claim_id']}] {row['identifier']} — {row['source_location']} "
        f"(content SHA-256 {row['content_sha256']}; investigator-verified {row['verified_at']})"
        for row in manuscript_records
    ) or "- No external claims cited."
    return (
        "# PathLab ADAPT — Evidence-bound draft\n\n## Abstract\n\n"
        f"Status: **{result.status}**. {contribution}\n\n## Introduction\n\n"
        "ADAPT is an educational, non-clinical workflow. It does not analyze slide pixels or generate medical answers.\n\n"
        f"## Prior evidence\n\n{prior_evidence}\n\n## Methods\n\n"
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
    exact_location: str
    content_sha256: str
    signoff: str
    verified_at: str
    approval_status: str = "pending"

    def validate(self) -> None:
        stable_internal = re.fullmatch(r"internal:[A-Za-z0-9._-]{8,128}", self.source_id) is not None
        if not (_DOI.fullmatch(self.source_id) or _PMID.fullmatch(self.source_id) or stable_internal):
            raise ValueError("safe-AI source requires a stable DOI, PMID, or approved internal content ID")
        if self.relationship != "unrelated":
            raise ValueError("safe-AI source requires an independently approved unrelated source")
        if not self.exact_location.strip():
            raise ValueError("safe-AI source requires an exact location")
        if re.fullmatch(r"[0-9a-f]{64}", self.content_sha256) is None:
            raise ValueError("safe-AI source requires a lowercase SHA-256 content hash")
        if not self.signoff.strip() or not self.verified_at.strip() or self.approval_status != "approved":
            raise ValueError("safe-AI source requires signoff and verification time")


def build_safe_ai_literacy_sequence(claim: EvidenceRecord, source: SourceApproval) -> tuple[dict[str, Any], ...]:
    claim.validate()
    source.validate()
    if (
        claim.resolved_claim_kind() != "safe_ai_fact"
        or not claim.claim_text.strip()
        or claim.truth_status != "verified_true"
    ):
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
