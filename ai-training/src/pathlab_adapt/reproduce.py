"""Single-command deterministic reproduction for ADAPT research artifacts."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .io import sha256_file, write_json_atomic
from .research import (
    AnalysisSnapshotV1,
    EvidenceRecord,
    EvidenceRegistry,
    NoveltyRegistry,
    NoveltySearch,
    StudyProtocol,
    analyze_normal_use,
    build_safe_ai_literacy_sequence,
    render_institutional_dossier,
    render_manuscript,
)


def _write_text(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def reproduce_study(config_path: Path, output_dir: Path) -> dict[str, Any]:
    config_path = Path(config_path).resolve()
    output_dir = Path(output_dir).resolve()
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError("reproduction output directory must be empty to preserve frozen artifacts")
    with config_path.open(encoding="utf-8") as handle:
        config = json.load(handle)
    if not isinstance(config, dict):
        raise ValueError("reproduction config must be a JSON object")
    output_dir.mkdir(parents=True, exist_ok=True)

    artifacts_raw = config.get("artifacts")
    if not isinstance(artifacts_raw, dict):
        raise ValueError("reproduction config requires an artifacts object")
    input_artifacts = {str(name): Path(str(path)).resolve() for name, path in artifacts_raw.items()}
    if "dataset" not in input_artifacts:
        raise ValueError("reproduction artifacts require a dataset")
    if any(not path.is_file() for path in input_artifacts.values()):
        raise ValueError("frozen input verification failed")

    novelty_rows = config.get("novelty_searches")
    if not isinstance(novelty_rows, list):
        raise ValueError("reproduction config requires novelty searches")
    novelty = NoveltyRegistry(
        tuple(NoveltySearch(**{**row, "result_ids": tuple(row.get("result_ids", ()))}) for row in novelty_rows),
        str(config["reviewer"]),
    ).freeze()
    evidence_rows = config.get("evidence")
    if not isinstance(evidence_rows, list):
        raise ValueError("reproduction config requires an evidence list")
    evidence = EvidenceRegistry(tuple(EvidenceRecord(**row) for row in evidence_rows)).freeze()
    baseline = config.get("baseline", [])
    followup = config.get("followup", [])
    if not isinstance(baseline, list) or not isinstance(followup, list):
        raise ValueError("baseline and followup must be lists")
    result = analyze_normal_use(baseline, followup, task_kind=str(config.get("task_kind", "keyed")))
    protocol = StudyProtocol.default()
    row_count_sources = {
        "baseline_count": "frozen baseline rows",
        "followup_count": "frozen follow-up rows",
        "matched_followups": "learner/task match over frozen rows",
        "attrition_count": "frozen baseline rows minus matched follow-up rows",
    }
    metric_sources = {key: "frozen matched follow-up rows" for key in result.metrics}
    citation_sources = {
        str(row["claim_id"]): f"{row['identifier']} at {row['source_location']}"
        for row in evidence["records"]
    }
    write_json_atomic(output_dir / "tables.json", {
        "schema_version": "AdaptTablesV1",
        "result": result.to_dict(),
        "number_sources": {**row_count_sources, **metric_sources},
        "citation_sources": citation_sources,
    })
    write_json_atomic(output_dir / "figure.json", {
        "schema_version": "AdaptFigureV1",
        "status": result.status,
        "series": [{"name": key, "value": value, "source": "tables.json"} for key, value in result.metrics.items()],
    })
    write_json_atomic(output_dir / "manuscript-inputs.json", {
        "schema_version": "ManuscriptInputsV1",
        "novelty_registry": novelty,
        "evidence_registry": evidence,
        "protocol": {
            "version": protocol.protocol_version,
            "design": protocol.design,
            "analysis_plan": protocol.analysis_plan,
            "withdrawal_policy": protocol.withdrawal_policy,
        },
        "result": result.to_dict(),
    })
    snapshot_artifacts = {
        **input_artifacts,
        "analysis_code": Path(__file__).resolve(),
        "table": output_dir / "tables.json",
        "figure": output_dir / "figure.json",
        "result": output_dir / "tables.json",
        "manuscript_inputs": output_dir / "manuscript-inputs.json",
    }
    snapshot = AnalysisSnapshotV1.freeze(
        str(config["snapshot_id"]),
        str(config["protocol_version"]),
        str(config["model_version"]),
        snapshot_artifacts,
    )
    if not snapshot.verify(snapshot_artifacts):
        raise ValueError("frozen snapshot verification failed")
    snapshot_payload = snapshot.to_dict()
    snapshot_payload["novelty_registry_sha256"] = novelty["sha256"]
    snapshot_payload["evidence_registry_sha256"] = evidence["sha256"]
    write_json_atomic(output_dir / "snapshot.json", snapshot_payload)
    manuscript = render_manuscript(snapshot, evidence, result)
    _write_text(output_dir / "manuscript.md", manuscript)
    _write_text(output_dir / "supplement.md", "# Supplement\n\n## Frozen novelty registry\n\n```json\n" + json.dumps(novelty, indent=2, sort_keys=True) + "\n```\n\n## Evidence registry\n\n```json\n" + json.dumps(evidence, indent=2, sort_keys=True) + "\n```\n")
    _write_text(output_dir / "dossier.md", render_institutional_dossier(protocol))
    write_json_atomic(output_dir / "safe-ai-literacy.json", {"schema_version": "SafeAILiteracyV1", "sequence": build_safe_ai_literacy_sequence("faculty-approved-true-claim", "faculty-approved-unrelated-source")})

    output_names = (
        "snapshot.json", "tables.json", "figure.json", "manuscript.md",
        "supplement.md", "dossier.md", "safe-ai-literacy.json", "manuscript-inputs.json",
    )
    output_hashes = {name: sha256_file(output_dir / name) for name in output_names}
    manifest: dict[str, Any] = {
        "schema_version": "ReproductionManifestV1",
        "command": "pathlab-adapt reproduce-study --config <frozen-config> --output-dir <empty-directory>",
        "status": result.status,
        "snapshot_sha256": snapshot.snapshot_sha256,
        "output_hashes": output_hashes,
        "verified": all(sha256_file(output_dir / name) == digest for name, digest in output_hashes.items()),
        "submission_performed": False,
    }
    write_json_atomic(output_dir / "reproduction-manifest.json", manifest)
    return manifest
