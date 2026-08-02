from __future__ import annotations

import inspect
import json
from dataclasses import replace
from pathlib import Path

import pytest

from pathlab_adapt.io import sha256_file
from pathlab_adapt.reproduce import reproduce_study, verify_safe_ai_literacy_artifact
from pathlab_adapt.research import (
    EvidenceRecord,
    EvidenceRegistry,
    SourceApproval,
    analyze_normal_use,
    render_manuscript,
)
from tests.test_adapt_research_hardening import complete_novelty, snapshot, valid_config


def signed_record(
    *, claim_text: str = "Background source material was registered."
) -> EvidenceRecord:
    return EvidenceRecord(
        "viewport", "PMID:26110095", "abstract:results", "observational", "background",
        "faculty-reviewer", "2026-08-02T12:00:00Z", claim_text=claim_text,
        content_sha256="b" * 64,
    )


def test_manuscript_has_no_free_form_contribution_override() -> None:
    assert "contribution_claim" not in inspect.signature(render_manuscript).parameters


def test_exact_prior_art_and_unavailable_review_force_fixed_non_novelty_wording(tmp_path: Path) -> None:
    evidence = EvidenceRegistry((signed_record(),)).freeze()
    exact = complete_novelty()
    exact["searches"][0]["screenings"][0]["decision"] = "include_exact"  # type: ignore[index]
    # Re-freezing is required; a tampered freeze must fail closed rather than trust its status.
    text = render_manuscript(snapshot(tmp_path), evidence, analyze_normal_use((), ()), novelty=exact)
    assert "pending or unavailable" in text
    assert "not locate" not in text

    unavailable = render_manuscript(snapshot(tmp_path), evidence, analyze_normal_use((), ()), novelty=complete_novelty(all_unavailable=True))
    assert "pending or unavailable" in unavailable
    assert "not locate" not in unavailable


def test_manuscript_renders_only_structured_engine_template_with_local_citation(
    tmp_path: Path,
) -> None:
    record = signed_record()
    manuscript = render_manuscript(
        snapshot(tmp_path), EvidenceRegistry((record,)).freeze(), analyze_normal_use((), ()), novelty=complete_novelty(),
    )
    assert f"{record.claim_text} [{record.claim_id}]" in manuscript
    assert "PMID:26110095 — abstract:results" in manuscript
    assert f"content SHA-256 {record.content_sha256}" in manuscript

    with pytest.raises(ValueError, match="claim text"):
        EvidenceRegistry((replace(record, claim_text=""),)).freeze()
    with pytest.raises(ValueError, match="citation markup"):
        EvidenceRegistry((replace(record, claim_text="Swapped source [other]."),)).freeze()


def test_free_text_number_cannot_bypass_structured_claim_template(tmp_path: Path) -> None:
    record = signed_record(claim_text="A 2015 viewport study was documented.")
    with pytest.raises(ValueError, match="structured claim template"):
        render_manuscript(
            snapshot(tmp_path),
            EvidenceRegistry((record,)).freeze(),
            analyze_normal_use((), ()),
            novelty=complete_novelty(),
        )


@pytest.mark.parametrize("word", [
    "increased", "enhances", "enhanced", "boosts", "boosted", "reduced", "decreased",
    "outperforms", "outperformed", "better", "superior", "benefits",
])
def test_manuscript_blocks_extended_positive_semantic_variants(tmp_path: Path, word: str) -> None:
    record = signed_record(claim_text=f"The system {word} delayed retention.")
    evidence = EvidenceRegistry((record,)).freeze()
    with pytest.raises(ValueError, match="structured claim template"):
        render_manuscript(snapshot(tmp_path), evidence, analyze_normal_use((), ()), novelty=complete_novelty())


@pytest.mark.parametrize("claim", [
    "The intervention raised delayed retention.",
    "The intervention yielded gains in delayed retention.",
    "The intervention strengthened delayed retention.",
    "The intervention produced greater delayed retention.",
    "Learners made fewer errors.",
    "Learners were more accurate.",
    "The intervention advanced delayed retention.",
    "The intervention optimized delayed retention.",
])
def test_manuscript_rejects_all_free_outcome_direction_claims(
    tmp_path: Path,
    claim: str,
) -> None:
    record = signed_record(claim_text=claim)
    evidence = EvidenceRegistry((record,)).freeze()
    with pytest.raises(ValueError, match="structured claim template"):
        render_manuscript(
            snapshot(tmp_path),
            evidence,
            analyze_normal_use((), ()),
            novelty=complete_novelty(),
        )


def test_manuscript_requires_an_unavailable_approved_outcome_artifact(
    tmp_path: Path,
) -> None:
    record = EvidenceRecord(
        "outcome",
        "PMID:26110095",
        "abstract:results",
        "observational",
        "approved_outcome",
        "faculty-reviewer",
        "2026-08-02T12:00:00Z",
        claim_text="Delayed correctness was greater.",
        content_sha256="b" * 64,
        claim_kind="approved_outcome",
    )
    evidence = EvidenceRegistry((record,)).freeze()
    with pytest.raises(ValueError, match="approved matched analysis/result artifact"):
        render_manuscript(
            snapshot(tmp_path),
            evidence,
            analyze_normal_use((), ()),
            novelty=complete_novelty(),
        )


def approved_safe_config(tmp_path: Path) -> Path:
    config = valid_config(tmp_path)
    payload = json.loads(config.read_text(encoding="utf-8"))
    payload["evidence"] = [{
        "claim_id": "truth", "identifier": "PMID:26110095", "source_location": "abstract:results",
        "study_design": "observational", "allowed_wording": "factual", "signoff": "faculty-a",
        "verified_at": "2026-08-02T12:00:00Z", "claim_text": "Faculty-verified true statement.",
        "truth_status": "verified_true", "content_sha256": "b" * 64,
    }]
    payload["safe_ai_literacy"] = {
        "claim_id": "truth",
        "source": {
            "source_id": "internal:unrelated-source-0001", "relationship": "unrelated",
            "exact_location": "curriculum/source-2#statement-1",
            "content_sha256": "a" * 64, "signoff": "faculty-b",
            "verified_at": "2026-08-02T12:05:00Z", "approval_status": "approved",
        },
    }
    config.write_text(json.dumps(payload, sort_keys=True), encoding="utf-8")
    return config


def test_safe_ai_source_rejects_arbitrary_string_and_missing_content_evidence() -> None:
    with pytest.raises(ValueError, match="stable DOI, PMID, or approved internal"):
        SourceApproval("source-2", "unrelated", "location", "a" * 64, "faculty", "2026-08-02", "approved").validate()
    with pytest.raises(ValueError, match="content hash"):
        SourceApproval("internal:unrelated-source-0001", "unrelated", "location", "", "faculty", "2026-08-02", "approved").validate()


def test_safe_ai_artifact_restores_full_signed_evidence_and_detects_tampering(tmp_path: Path) -> None:
    output = tmp_path / "out"
    reproduce_study(approved_safe_config(tmp_path), output)
    safe = json.loads((output / "safe-ai-literacy.json").read_text(encoding="utf-8"))
    assert safe["claim_evidence"]["identifier"] == "PMID:26110095"
    assert safe["claim_evidence"]["source_location"] == "abstract:results"
    assert safe["claim_evidence"]["content_sha256"] == "b" * 64
    assert safe["source_evidence"]["source_id"] == "internal:unrelated-source-0001"
    assert safe["source_evidence"]["content_sha256"] == "a" * 64
    assert verify_safe_ai_literacy_artifact(safe)
    content_tamper = json.loads(json.dumps(safe))
    content_tamper["source_evidence"]["content_sha256"] = "c" * 64
    assert not verify_safe_ai_literacy_artifact(content_tamper)
    safe["source_evidence"]["relationship"] = "supports"
    assert not verify_safe_ai_literacy_artifact(safe)
    manuscript_inputs = json.loads((output / "manuscript-inputs.json").read_text(encoding="utf-8"))
    assert manuscript_inputs["safe_ai_literacy"]["source_evidence"]["approval_status"] == "approved"


def test_snapshot_binds_config_full_code_and_deterministic_environment(tmp_path: Path) -> None:
    config = valid_config(tmp_path)
    output = tmp_path / "out"
    reproduce_study(config, output)
    frozen = json.loads((output / "snapshot.json").read_text(encoding="utf-8"))
    expected = {
        "config_canonical", "analysis_code_research", "analysis_code_reproduce", "analysis_code_io",
        "analysis_code_cli", "analysis_code_init", "pyproject", "environment_manifest",
    }
    assert expected.issubset(frozen["artifact_hashes"])
    assert frozen["config_canonical_sha256"] == sha256_file(output / "config-canonical.json")
    environment = json.loads((output / "environment.json").read_text(encoding="utf-8"))
    assert set(environment) == {
        "schema_version", "python_version", "python_implementation", "operating_system",
        "machine_architecture", "pathlab_adapt_version", "package_versions", "dependency_capture",
    }
    assert environment["package_versions"] == {"pathlab-ai-data": environment["pathlab_adapt_version"]}
    serialized = json.dumps(environment)
    assert str(tmp_path) not in serialized
    assert "hostname" not in serialized.lower()


def test_canonical_config_hash_is_whitespace_and_path_independent(tmp_path: Path) -> None:
    config_a = valid_config(tmp_path)
    payload = json.loads(config_a.read_text(encoding="utf-8"))
    config_b = tmp_path / "config-b.json"
    config_b.write_text(json.dumps(payload, indent=4), encoding="utf-8")
    first = reproduce_study(config_a, tmp_path / "out-a")
    second = reproduce_study(config_b, tmp_path / "out-b")
    assert first["output_hashes"] == second["output_hashes"]
