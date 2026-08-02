from __future__ import annotations

import hashlib
import json
from dataclasses import replace
from pathlib import Path

import pytest

import pathlab_adapt.reproduce as reproduce_module
from pathlab_adapt.cli import main
from pathlab_adapt.reproduce import MAX_CONFIG_BYTES, reproduce_study
from pathlab_adapt.research import (
    PRIOR_ART_SOURCES,
    AnalysisSnapshotV1,
    EvidenceRecord,
    EvidenceRegistry,
    NoveltyRegistry,
    NoveltySearch,
    ScreeningRecord,
    SourceApproval,
    analyze_normal_use,
    build_safe_ai_literacy_sequence,
    render_manuscript,
)


def complete_novelty(*, all_unavailable: bool = False) -> dict[str, object]:
    searches = []
    for database in PRIOR_ART_SOURCES:
        if all_unavailable:
            searches.append(NoveltySearch(database, "exact frozen query", "2026-08-02", "unavailable", (), "access unavailable", ()))
        else:
            result_id = f"{database}:stable-1"
            searches.append(NoveltySearch(
                database, "exact frozen query", "2026-08-02", "searched", (result_id,), "",
                (ScreeningRecord(result_id, "exclude", "adjacent system; exact combination absent"),),
            ))
    return NoveltyRegistry(tuple(searches), "faculty-reviewer", "2026-08-02T12:00:00Z").freeze()


def partial_unavailable_novelty() -> dict[str, object]:
    searches = []
    for database in PRIOR_ART_SOURCES:
        if database == "scopus":
            searches.append(NoveltySearch(
                database, "exact frozen query", "2026-08-02", "unavailable", (),
                "subscription unavailable", (),
            ))
            continue
        result_id = f"{database}:stable-1"
        searches.append(NoveltySearch(
            database, "exact frozen query", "2026-08-02", "searched", (result_id,), "",
            (ScreeningRecord(result_id, "exclude", "adjacent system; exact combination absent"),),
        ))
    return NoveltyRegistry(
        tuple(searches), "faculty-reviewer", "2026-08-02T12:00:00Z"
    ).freeze()


def evidence_registry() -> dict[str, object]:
    return EvidenceRegistry((EvidenceRecord(
        "prior-vmat", "PMID:26110095", "abstract:methods", "observational", "association",
        "faculty-reviewer", "2026-08-02T12:00:00Z", claim_text="An association was reported.",
        content_sha256="b" * 64,
    ),)).freeze()


def snapshot(tmp_path: Path) -> AnalysisSnapshotV1:
    artifact = tmp_path / "dataset.json"
    artifact.write_text("{}\n", encoding="utf-8")
    return AnalysisSnapshotV1.freeze("snap", "protocol", "fixed-order", {"dataset": artifact})


def test_novelty_freeze_requires_exact_source_matrix() -> None:
    one = NoveltySearch("pubmed", "q", "2026-08-02", "unavailable", (), "not accessible", ())
    with pytest.raises(ValueError, match="matrix"):
        NoveltyRegistry((one,), "faculty", "2026-08-02T00:00:00Z").freeze()
    unknown = tuple(
        NoveltySearch(source, "q", "2026-08-02", "unavailable", (), "not accessible", ())
        for source in (*PRIOR_ART_SOURCES[:-1], "unknown-source")
    )
    with pytest.raises(ValueError, match="matrix"):
        NoveltyRegistry(unknown, "faculty", "2026-08-02T00:00:00Z").freeze()


def test_searched_novelty_requires_one_structured_screening_per_result() -> None:
    rows = [NoveltySearch(source, "q", "2026-08-02", "unavailable", (), "not accessible", ()) for source in PRIOR_ART_SOURCES]
    rows[0] = NoveltySearch("pubmed", "q", "2026-08-02", "searched", ("PMID:1",), "screened substring", ())
    with pytest.raises(ValueError, match="screening"):
        NoveltyRegistry(tuple(rows), "faculty", "2026-08-02T00:00:00Z").freeze()
    rows[0] = NoveltySearch("pubmed", "q", "2026-08-02", "searched", ("",), "", (ScreeningRecord("", "exclude", "reason"),))
    with pytest.raises(ValueError, match="stable result"):
        NoveltyRegistry(tuple(rows), "faculty", "2026-08-02T00:00:00Z").freeze()


def test_manuscript_novelty_claim_fails_closed_when_review_unavailable(tmp_path: Path) -> None:
    manuscript = render_manuscript(snapshot(tmp_path), evidence_registry(), analyze_normal_use((), ()), novelty=complete_novelty(all_unavailable=True))
    assert "Formal prior-art review pending or unavailable" in manuscript
    assert "combination was not located" not in manuscript


def test_any_unavailable_source_keeps_novelty_review_partial_and_blocks_not_located(
    tmp_path: Path,
) -> None:
    novelty = partial_unavailable_novelty()
    assert novelty["formal_review_status"] == "partial_unavailable"

    manuscript = render_manuscript(
        snapshot(tmp_path), evidence_registry(), analyze_normal_use((), ()), novelty=novelty,
    )
    assert "Formal prior-art review pending or unavailable" in manuscript
    assert "did not locate" not in manuscript


def test_complete_novelty_requires_every_source_searched_and_screened(tmp_path: Path) -> None:
    novelty = complete_novelty()
    assert novelty["formal_review_status"] == "complete"
    manuscript = render_manuscript(
        snapshot(tmp_path), evidence_registry(), analyze_normal_use((), ()), novelty=novelty,
    )
    assert "signed formal review did not locate" in manuscript


def test_exact_prior_art_overrides_partial_unavailable_status(tmp_path: Path) -> None:
    novelty = partial_unavailable_novelty()
    first_screening = novelty["searches"][0]["screenings"][0]  # type: ignore[index]
    first_screening["decision"] = "include_exact"
    searches = tuple(
        NoveltySearch(
            database=row["database"],
            query=row["query"],
            run_date=row["run_date"],
            status=row["status"],
            result_ids=tuple(row["result_ids"]),
            reason=row["reason"],
            screenings=tuple(ScreeningRecord(**screening) for screening in row["screenings"]),
        )
        for row in novelty["searches"]  # type: ignore[union-attr]
    )
    refrozen = NoveltyRegistry(
        searches, "faculty-reviewer", "2026-08-02T12:00:00Z"
    ).freeze()
    assert refrozen["formal_review_status"] == "exact_prior_art_found"
    manuscript = render_manuscript(
        snapshot(tmp_path), evidence_registry(), analyze_normal_use((), ()), novelty=refrozen,
    )
    assert "contribution must be narrowed" in manuscript
    assert "did not locate" not in manuscript


def test_manuscript_revalidates_frozen_novelty_matrix_not_only_digest(tmp_path: Path) -> None:
    forged = complete_novelty()
    forged["searches"] = forged["searches"][:-1]  # type: ignore[index]
    body = {key: forged[key] for key in ("schema_version", "reviewer", "signed_at", "formal_review_status", "searches")}
    forged["sha256"] = hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()
    manuscript = render_manuscript(snapshot(tmp_path), evidence_registry(), analyze_normal_use((), ()), novelty=forged)
    assert "Formal prior-art review pending or unavailable" in manuscript


@pytest.mark.parametrize("identifier", ["doi:10.1/x", "doi:not-a-doi", "PMID:abc", "PMID:"])
def test_evidence_registry_rejects_malformed_identifiers(identifier: str) -> None:
    with pytest.raises(ValueError, match="DOI or PMID"):
        EvidenceRegistry((EvidenceRecord("c", identifier, "abstract", "observational", "association", "faculty", "2026-08-02"),)).freeze()


def test_evidence_registry_rejects_empty_enums_and_design_wording_mismatch() -> None:
    with pytest.raises(ValueError, match="study design"):
        EvidenceRegistry((EvidenceRecord("c", "PMID:1", "abstract", "", "association", "faculty", "2026-08-02"),)).freeze()
    with pytest.raises(ValueError, match="incompatible"):
        EvidenceRegistry((EvidenceRecord("c", "PMID:1", "abstract", "methods", "association", "faculty", "2026-08-02"),)).freeze()


@pytest.mark.parametrize("claim", [
    "ADAPT leads to better retention.", "ADAPT resulted in benefit.", "ADAPT has a positive effect.",
    "ADAPT improves learning.", "ADAPT was effective.", "ADAPT caused higher scores.",
])
def test_claim_gate_blocks_positive_and_causal_variants(tmp_path: Path, claim: str) -> None:
    record = EvidenceRecord(
        "claim", "PMID:26110095", "abstract:results", "observational", "background",
        "faculty", "2026-08-02T12:00:00Z", claim_text=claim, content_sha256="b" * 64,
    )
    with pytest.raises(ValueError, match="structured claim template"):
        render_manuscript(snapshot(tmp_path), EvidenceRegistry((record,)).freeze(), analyze_normal_use((), ()), novelty=complete_novelty())


def test_citation_must_use_record_allowed_wording(tmp_path: Path) -> None:
    invalid = EvidenceRecord(
        "prior-vmat", "PMID:26110095", "abstract", "observational", "association",
        "faculty", "2026-08-02T12:00:00Z", claim_text="Viewport behavior was documented.", content_sha256="b" * 64,
    )
    with pytest.raises(ValueError, match="structured claim template"):
        render_manuscript(snapshot(tmp_path), EvidenceRegistry((invalid,)).freeze(), analyze_normal_use((), ()), novelty=complete_novelty())
    valid = replace(invalid, claim_text="An association was reported.")
    text = render_manuscript(
        snapshot(tmp_path), EvidenceRegistry((valid,)).freeze(), analyze_normal_use((), ()), novelty=complete_novelty(),
    )
    assert "association was reported" in text


def test_manuscript_revalidates_evidence_signoff_not_only_payload_shape(tmp_path: Path) -> None:
    forged = evidence_registry()
    forged["records"][0]["signoff"] = ""  # type: ignore[index]
    body = {"schema_version": "EvidenceRegistryV1", "records": forged["records"]}
    forged["sha256"] = hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()
    with pytest.raises(ValueError, match="evidence registry"):
        render_manuscript(snapshot(tmp_path), forged, analyze_normal_use((), ()), novelty=complete_novelty())


def test_any_missing_or_unmatched_pair_is_inconclusive() -> None:
    baseline = (
        {"learner_id": "l1", "task_id": "t1", "success": True, "active_minutes": 1, "hints": 0},
        {"learner_id": "l2", "task_id": "t2", "success": True, "active_minutes": 1, "hints": 0},
    )
    followup = (
        {"learner_id": "l1", "task_id": "t1", "success": True, "active_minutes": 1, "hints": 0},
        {"learner_id": "l3", "task_id": "t3", "success": True, "active_minutes": 1, "hints": 0},
    )
    result = analyze_normal_use(baseline, followup)
    assert result.status == "inconclusive"
    assert result.eligible_pairs == 2
    assert result.matched_followups == 1
    assert result.attrition_count == 1
    assert result.unmatched_followups == 1


def test_snapshot_verification_rejects_metadata_forgery(tmp_path: Path) -> None:
    original = snapshot(tmp_path)
    assert original.verify({"dataset": tmp_path / "dataset.json"})
    assert not replace(original, snapshot_id="forged").verify({"dataset": tmp_path / "dataset.json"})
    assert not replace(original, artifact_hashes=(("dataset", "0" * 64),)).verify({"dataset": tmp_path / "dataset.json"})


def test_safe_ai_sequence_requires_signed_true_claim_and_unrelated_source() -> None:
    claim = EvidenceRecord("truth", "PMID:26110095", "abstract:result", "observational", "factual", "faculty", "2026-08-02T12:00:00Z", claim_text="Faculty-approved true claim", truth_status="verified_true", content_sha256="b" * 64)
    unrelated = SourceApproval(
        "internal:unrelated-source-0001", "unrelated", "curriculum/source-2#statement",
        "a" * 64, "faculty", "2026-08-02", "approved",
    )
    sequence = build_safe_ai_literacy_sequence(claim, unrelated)
    assert sequence[1]["claim_id"] == "truth"
    with pytest.raises(ValueError, match="unrelated"):
        build_safe_ai_literacy_sequence(claim, replace(unrelated, relationship="supports"))
    with pytest.raises(ValueError, match="verified true"):
        build_safe_ai_literacy_sequence(replace(claim, truth_status="unverified"), unrelated)


def valid_config(tmp_path: Path) -> Path:
    artifact = tmp_path / "events.json"
    artifact.write_text("[]\n", encoding="utf-8")
    searches = []
    for source in PRIOR_ART_SOURCES:
        searches.append({"database": source, "query": "q", "run_date": "2026-08-02", "status": "unavailable", "result_ids": [], "reason": "not accessed in this run", "screenings": []})
    path = tmp_path / "config.json"
    path.write_text(json.dumps({
        "snapshot_id": "snap", "protocol_version": "p", "model_version": "fixed-order-v1",
        "artifacts": {"dataset": str(artifact)}, "reviewer": "faculty", "signed_at": "2026-08-02",
        "novelty_searches": searches, "evidence": [], "baseline": [], "followup": [],
    }), encoding="utf-8")
    return path


def test_reproduction_is_transactional_and_safe_ai_is_pending(tmp_path: Path) -> None:
    config = valid_config(tmp_path)
    output = tmp_path / "out"
    manifest = reproduce_study(config, output)
    safe = json.loads((output / "safe-ai-literacy.json").read_text(encoding="utf-8"))
    assert safe == {"schema_version": "SafeAILiteracyV1", "status": "pending_faculty_evidence", "sequence": []}
    assert manifest["status"] == "inconclusive"
    assert not list(tmp_path.glob(".out.*.partial"))


def test_invalid_config_leaves_no_partial_output_and_cli_normalizes_error(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    config = valid_config(tmp_path)
    payload = json.loads(config.read_text(encoding="utf-8"))
    del payload["snapshot_id"]
    config.write_text(json.dumps(payload), encoding="utf-8")
    output = tmp_path / "out"
    assert main(["reproduce-study", "--config", str(config), "--output-dir", str(output)]) == 2
    assert "failed:" in capsys.readouterr().err
    assert not output.exists()


def test_config_size_cap_is_enforced_before_output_creation(tmp_path: Path) -> None:
    config = tmp_path / "huge.json"
    config.write_bytes(b" " * (MAX_CONFIG_BYTES + 1))
    output = tmp_path / "out"
    with pytest.raises(ValueError, match="size cap"):
        reproduce_study(config, output)
    assert not output.exists()


def test_malformed_rows_are_normalized_and_transaction_cleanup_is_complete(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    config = valid_config(tmp_path)
    payload = json.loads(config.read_text(encoding="utf-8"))
    payload["baseline"] = [{"learner_id": "l1", "task_id": "t1"}]
    config.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="row"):
        reproduce_study(config, tmp_path / "bad-out")
    assert not (tmp_path / "bad-out").exists()

    config = valid_config(tmp_path)
    monkeypatch.setattr(reproduce_module, "MAX_OUTPUT_BYTES", 1)
    with pytest.raises(ValueError, match="output size cap"):
        reproduce_study(config, tmp_path / "capped-out")
    assert not (tmp_path / "capped-out").exists()
    assert not list(tmp_path.glob(".capped-out.*.partial"))


def test_snapshot_binds_both_research_and_reproduction_code(tmp_path: Path) -> None:
    output = tmp_path / "out"
    reproduce_study(valid_config(tmp_path), output)
    frozen = json.loads((output / "snapshot.json").read_text(encoding="utf-8"))["artifact_hashes"]
    assert "analysis_code_research" in frozen
    assert "analysis_code_reproduce" in frozen
