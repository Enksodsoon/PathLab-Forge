from __future__ import annotations

import json
from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest

from pathlab_adapt.research import (
    AnalysisSnapshotV1,
    EvidenceRecord,
    EvidenceRegistry,
    NoveltyRegistry,
    NoveltySearch,
    PRIOR_ART_SOURCES,
    StudyProtocol,
    analyze_normal_use,
    build_safe_ai_literacy_sequence,
    render_institutional_dossier,
    render_manuscript,
)
from pathlab_adapt.reproduce import reproduce_study
from pathlab_adapt.cli import main


def test_novelty_freeze_keeps_unavailable_paid_sources_explicit() -> None:
    registry = NoveltyRegistry(
        searches=(
            NoveltySearch("pubmed", "telemetry retention pathology", "2026-08-02", "searched", ("PMID:1",), "screened: adjacent"),
            NoveltySearch("scopus", "telemetry retention pathology", "2026-08-02", "unavailable", (), "subscription unavailable"),
        ),
        reviewer="faculty-1",
    )

    frozen = registry.freeze()

    assert frozen["searches"][1]["status"] == "unavailable"
    assert frozen["searches"][1]["reason"] == "subscription unavailable"
    assert frozen["sha256"] == registry.freeze()["sha256"]


def test_formal_prior_art_source_matrix_is_explicit() -> None:
    assert PRIOR_ART_SOURCES == (
        "pubmed", "pmc", "crossref", "openalex", "arxiv", "ieee-xplore", "acm-digital-library",
        "scopus", "web-of-science", "trial-registries", "wipo-patentscope", "google-patents", "product-documentation",
    )


def test_novelty_registry_rejects_claimed_search_without_screening_records() -> None:
    with pytest.raises(ValueError, match="screening"):
        NoveltyRegistry(
            searches=(NoveltySearch("pubmed", "query", "2026-08-02", "searched"),),
            reviewer="faculty-1",
        ).freeze()


def test_evidence_registry_requires_source_location_and_signoff() -> None:
    with pytest.raises(ValueError, match="source location"):
        EvidenceRegistry((EvidenceRecord("c1", "doi:10.1/x", "", "observational", "association", "faculty", "2026-08-02"),)).freeze()


def test_snapshot_is_immutable_and_verifies_all_input_hashes(tmp_path: Path) -> None:
    dataset = tmp_path / "dataset.json"
    code = tmp_path / "analysis.py"
    dataset.write_text("{}\n", encoding="utf-8")
    code.write_text("# frozen\n", encoding="utf-8")
    snapshot = AnalysisSnapshotV1.freeze(
        snapshot_id="snap-1",
        protocol_version="protocol-1",
        model_version="fixed-order-v1",
        artifacts={"dataset": dataset, "code": code},
    )

    assert snapshot.verify({"dataset": dataset, "code": code})
    with pytest.raises(FrozenInstanceError):
        snapshot.snapshot_id = "changed"  # type: ignore[misc]
    dataset.write_text('{"changed":true}\n', encoding="utf-8")
    assert not snapshot.verify({"dataset": dataset, "code": code})


def test_missing_or_unmatched_followup_is_inconclusive() -> None:
    baseline = ({"learner_id": "l1", "task_id": "t1", "success": True, "active_minutes": 2, "hints": 1},)
    unmatched = ({"learner_id": "l2", "task_id": "t1", "success": True, "active_minutes": 1, "hints": 0},)

    result = analyze_normal_use(baseline, unmatched)

    assert result.status == "inconclusive"
    assert result.matched_followups == 0
    assert result.model_fit is None
    assert result.primary_outcome == "delayed correctness"


def test_spatial_metrics_show_efficiency_beside_raw_metrics() -> None:
    baseline = ({"learner_id": "l1", "task_id": "t1", "success": False, "active_minutes": 2, "hints": 1},)
    followup = ({"learner_id": "l1", "task_id": "t1", "success": True, "active_minutes": 4, "hints": 2},)

    result = analyze_normal_use(baseline, followup, task_kind="spatial")

    assert result.primary_outcome == "success within author-defined coordinate tolerance"
    assert result.metrics == {
        "raw_correctness": 1.0,
        "active_minutes": 4.0,
        "hint_count": 2,
        "retention_adjusted_efficiency": 0.25,
    }
    assert result.status == "descriptive_only"
    assert result.model_fit is None


def test_manuscript_blocks_unsupported_positive_causal_and_first_ever_wording(tmp_path: Path) -> None:
    artifact = tmp_path / "dataset.json"
    artifact.write_text("{}\n", encoding="utf-8")
    snapshot = AnalysisSnapshotV1.freeze("s", "p", "fixed", {"dataset": artifact})
    evidence = EvidenceRegistry((EvidenceRecord("c1", "PMID:26110095", "abstract: methods", "observational", "association", "faculty", "2026-08-02"),)).freeze()
    result = analyze_normal_use((), ())

    for claim in ("ADAPT caused improvement.", "ADAPT is effective.", "This is the first ever system."):
        with pytest.raises(ValueError, match="unsupported wording"):
            render_manuscript(snapshot, evidence, result, contribution_claim=claim)


def test_manuscript_rejects_unknown_citation_and_untraced_number(tmp_path: Path) -> None:
    artifact = tmp_path / "dataset.json"
    artifact.write_text("{}\n", encoding="utf-8")
    snapshot = AnalysisSnapshotV1.freeze("s", "p", "fixed", {"dataset": artifact})
    evidence = EvidenceRegistry((EvidenceRecord("c1", "PMID:26110095", "abstract", "observational", "association", "faculty", "2026-08-02"),)).freeze()
    result = analyze_normal_use((), ())

    with pytest.raises(ValueError, match="citation"):
        render_manuscript(snapshot, evidence, result, contribution_claim="To our knowledge, this combination was not located [c2].")
    with pytest.raises(ValueError, match="number"):
        render_manuscript(snapshot, evidence, result, contribution_claim="To our knowledge, the records included 99 learners [c1].")


def test_safe_ai_literacy_sequence_is_fixed_and_debriefs_mismatch() -> None:
    sequence = build_safe_ai_literacy_sequence("claim-1", "source-1")

    assert [step["kind"] for step in sequence] == ["independent_answer", "true_claim_unrelated_source", "source_check", "immediate_debrief"]
    assert all(step["randomized"] is False for step in sequence)


def test_dossier_names_authority_and_required_safeguards() -> None:
    dossier = render_institutional_dossier(StudyProtocol.default())

    assert "institutional authority" in dossier.lower()
    assert "Codex does not determine" in dossier
    for section in ("Prohibited use", "Consent", "Withdrawal", "Accessibility", "Rollback", "Manuscript plan"):
        assert section in dossier


def test_single_command_reproduction_is_deterministic_and_hash_verified(tmp_path: Path) -> None:
    input_artifact = tmp_path / "events.json"
    input_artifact.write_text("[]\n", encoding="utf-8")
    config = tmp_path / "study.json"
    config.write_text(json.dumps({
        "snapshot_id": "snap-1",
        "protocol_version": "protocol-1",
        "model_version": "fixed-order-v1",
        "artifacts": {"dataset": str(input_artifact)},
        "novelty_searches": [
            {"database": "pubmed", "query": "adapt pathology", "run_date": "2026-08-02", "status": "searched", "result_ids": ["PMID:26110095"], "reason": "screened: adjacent"},
            {"database": "scopus", "query": "adapt pathology", "run_date": "2026-08-02", "status": "unavailable", "result_ids": [], "reason": "subscription unavailable"},
        ],
        "reviewer": "faculty-1",
        "evidence": [{"claim_id": "prior-vmat", "identifier": "PMID:26110095", "source_location": "abstract", "study_design": "observational", "allowed_wording": "association", "signoff": "faculty-1", "verified_at": "2026-08-02"}],
        "baseline": [],
        "followup": [],
    }, sort_keys=True), encoding="utf-8")
    first = tmp_path / "out-1"
    second = tmp_path / "out-2"

    manifest_a = reproduce_study(config, first)
    manifest_b = reproduce_study(config, second)

    assert manifest_a["status"] == "inconclusive"
    assert manifest_a["output_hashes"] == manifest_b["output_hashes"]
    assert (first / "manuscript.md").read_text(encoding="utf-8") == (second / "manuscript.md").read_text(encoding="utf-8")
    assert all((first / name).is_file() for name in ("snapshot.json", "tables.json", "figure.json", "manuscript.md", "supplement.md", "dossier.md", "reproduction-manifest.json"))
    tables = json.loads((first / "tables.json").read_text(encoding="utf-8"))
    assert set(tables["number_sources"]) == {
        "baseline_count", "followup_count", "matched_followups", "attrition_count",
        "raw_correctness", "active_minutes", "hint_count", "retention_adjusted_efficiency",
    }
    assert tables["citation_sources"] == {"prior-vmat": "PMID:26110095 at abstract"}
    snapshot = json.loads((first / "snapshot.json").read_text(encoding="utf-8"))
    assert {"dataset", "analysis_code", "table", "figure", "result", "manuscript_inputs"}.issubset(snapshot["artifact_hashes"])


def test_reproduce_study_cli_runs_offline(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    artifact = tmp_path / "events.json"
    artifact.write_text("[]\n", encoding="utf-8")
    config = tmp_path / "config.json"
    config.write_text(json.dumps({
        "snapshot_id": "snap-cli", "protocol_version": "p1", "model_version": "fixed-order-v1",
        "artifacts": {"dataset": str(artifact)}, "reviewer": "faculty",
        "novelty_searches": [{"database": "pubmed", "query": "q", "run_date": "2026-08-02", "status": "searched", "result_ids": ["PMID:1"], "reason": "screened: adjacent"}],
        "evidence": [], "baseline": [], "followup": [],
    }), encoding="utf-8")
    output = tmp_path / "out"

    assert main(["reproduce-study", "--config", str(config), "--output-dir", str(output)]) == 0
    assert json.loads(capsys.readouterr().out)["status"] == "inconclusive"

    with pytest.raises(ValueError, match="empty"):
        reproduce_study(config, output)
