from __future__ import annotations

from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest

from pathlab_adapt.research import (
    PRIOR_ART_SOURCES,
    AnalysisSnapshotV1,
    StudyProtocol,
    analyze_normal_use,
    render_institutional_dossier,
)


def test_formal_prior_art_source_matrix_is_explicit() -> None:
    assert PRIOR_ART_SOURCES == (
        "pubmed", "pmc", "crossref", "openalex", "arxiv", "ieee-xplore", "acm-digital-library",
        "scopus", "web-of-science", "trial-registries", "wipo-patentscope", "google-patents", "product-documentation",
    )


def test_snapshot_is_frozen_and_verifies_all_input_hashes(tmp_path: Path) -> None:
    dataset = tmp_path / "dataset.json"
    code = tmp_path / "analysis.py"
    dataset.write_text("{}\n", encoding="utf-8")
    code.write_text("# frozen\n", encoding="utf-8")
    snapshot = AnalysisSnapshotV1.freeze("snap-1", "protocol-1", "fixed-order-v1", {"dataset": dataset, "code": code})
    assert snapshot.verify({"dataset": dataset, "code": code})
    with pytest.raises(FrozenInstanceError):
        snapshot.snapshot_id = "changed"  # type: ignore[misc]
    dataset.write_text('{"changed":true}\n', encoding="utf-8")
    assert not snapshot.verify({"dataset": dataset, "code": code})


def test_no_followup_is_inconclusive() -> None:
    baseline = ({"learner_id": "l1", "task_id": "t1", "success": True, "active_minutes": 2, "hints": 1},)
    result = analyze_normal_use(baseline, ())
    assert result.status == "inconclusive"
    assert result.matched_followups == 0
    assert result.attrition_count == 1
    assert result.model_fit is None
    assert result.primary_outcome == "delayed correctness"


def test_fully_matched_spatial_metrics_show_efficiency_beside_raw_metrics() -> None:
    baseline = ({"learner_id": "l1", "task_id": "t1", "success": False, "active_minutes": 2, "hints": 1},)
    followup = ({"learner_id": "l1", "task_id": "t1", "success": True, "active_minutes": 4, "hints": 2},)
    result = analyze_normal_use(baseline, followup, task_kind="spatial")
    assert result.primary_outcome == "success within author-defined coordinate tolerance"
    assert result.metrics == {
        "raw_correctness": 1.0, "active_minutes": 4.0, "hint_count": 2,
        "retention_adjusted_efficiency": 0.25,
    }
    assert result.status == "descriptive_only"
    assert result.model_fit is None


def test_dossier_names_authority_and_required_safeguards() -> None:
    dossier = render_institutional_dossier(StudyProtocol.default())
    assert "institutional authority" in dossier.lower()
    assert "Codex does not determine" in dossier
    for section in ("Prohibited use", "Consent", "Withdrawal", "Accessibility", "Rollback", "Manuscript plan"):
        assert section in dossier
