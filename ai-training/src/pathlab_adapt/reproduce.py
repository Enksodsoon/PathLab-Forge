"""Transactional single-command reproduction for ADAPT research artifacts."""

from __future__ import annotations

import json
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any, Mapping

from . import research as research_module
from .io import sha256_file, write_json_atomic
from .research import (
    AnalysisSnapshotV1,
    EvidenceRecord,
    EvidenceRegistry,
    NoveltyRegistry,
    NoveltySearch,
    ScreeningRecord,
    SourceApproval,
    StudyProtocol,
    analyze_normal_use,
    build_safe_ai_literacy_sequence,
    render_institutional_dossier,
    render_manuscript,
)


MAX_CONFIG_BYTES = 2 * 1024 * 1024
MAX_RECORDS = 100_000
MAX_STRING_CHARS = 10_000
MAX_STRUCTURE_DEPTH = 20
MAX_OUTPUT_BYTES = 20 * 1024 * 1024


def _write_text_atomic(path: Path, text: str) -> None:
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", prefix=f".{path.name}.",
            suffix=".partial", dir=path.parent, delete=False,
        ) as handle:
            temporary = Path(handle.name)
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _validate_bounded(value: object, *, depth: int = 0, counter: list[int] | None = None) -> None:
    if depth > MAX_STRUCTURE_DEPTH:
        raise ValueError("configuration nesting exceeds depth cap")
    counter = [0] if counter is None else counter
    counter[0] += 1
    if counter[0] > MAX_RECORDS:
        raise ValueError("configuration structure exceeds record cap")
    if isinstance(value, str):
        if len(value) > MAX_STRING_CHARS:
            raise ValueError("configuration string exceeds length cap")
    elif isinstance(value, Mapping):
        if len(value) > MAX_RECORDS:
            raise ValueError("configuration object exceeds record cap")
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError("configuration object keys must be strings")
            _validate_bounded(key, depth=depth + 1, counter=counter)
            _validate_bounded(item, depth=depth + 1, counter=counter)
    elif isinstance(value, list):
        if len(value) > MAX_RECORDS:
            raise ValueError("configuration list exceeds record cap")
        for item in value:
            _validate_bounded(item, depth=depth + 1, counter=counter)
    elif value is not None and not isinstance(value, (bool, int, float)):
        raise ValueError("configuration contains an unsupported value")


def _required_text(config: Mapping[str, Any], key: str) -> str:
    value = config.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"reproduction config requires nonblank {key}")
    return value


def _parse_search(row: object) -> NoveltySearch:
    if not isinstance(row, dict):
        raise ValueError("novelty search rows must be objects")
    screenings = row.get("screenings", [])
    if not isinstance(screenings, list):
        raise ValueError("novelty screenings must be a list")
    result_ids = row.get("result_ids", [])
    if not isinstance(result_ids, list):
        raise ValueError("novelty result_ids must be a list")
    try:
        return NoveltySearch(
            database=str(row["database"]), query=str(row["query"]),
            run_date=str(row["run_date"]), status=str(row["status"]),
            result_ids=tuple(str(item) for item in result_ids), reason=str(row.get("reason", "")),
            screenings=tuple(ScreeningRecord(**item) for item in screenings),
        )
    except (KeyError, TypeError) as error:
        raise ValueError(f"invalid novelty search row: {error}") from error


def _parse_evidence(row: object) -> EvidenceRecord:
    if not isinstance(row, dict):
        raise ValueError("evidence rows must be objects")
    try:
        return EvidenceRecord(**row)
    except TypeError as error:
        raise ValueError(f"invalid evidence row: {error}") from error


def _prepare(config_path: Path, output_dir: Path) -> tuple[dict[str, Any], dict[str, Path], dict[str, Any], dict[str, Any], Any, StudyProtocol, dict[str, Any]]:
    if not config_path.is_file():
        raise ValueError("reproduction config does not exist")
    if config_path.stat().st_size > MAX_CONFIG_BYTES:
        raise ValueError("reproduction config exceeds size cap")
    if output_dir.exists():
        raise ValueError("reproduction output directory must not exist")
    try:
        with config_path.open(encoding="utf-8") as handle:
            config = json.load(handle)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid reproduction JSON: {error}") from error
    if not isinstance(config, dict):
        raise ValueError("reproduction config must be a JSON object")
    _validate_bounded(config)
    for key in ("snapshot_id", "protocol_version", "model_version", "reviewer", "signed_at"):
        _required_text(config, key)
    artifacts_raw = config.get("artifacts")
    if not isinstance(artifacts_raw, dict) or "dataset" not in artifacts_raw:
        raise ValueError("reproduction artifacts require a dataset")
    input_artifacts = {str(name): Path(str(path)).resolve() for name, path in artifacts_raw.items()}
    if any(not name.strip() or not path.is_file() for name, path in input_artifacts.items()):
        raise ValueError("frozen input verification failed")
    novelty_rows = config.get("novelty_searches")
    if not isinstance(novelty_rows, list):
        raise ValueError("reproduction config requires novelty searches")
    novelty = NoveltyRegistry(
        tuple(_parse_search(row) for row in novelty_rows),
        _required_text(config, "reviewer"), _required_text(config, "signed_at"),
    ).freeze()
    evidence_rows = config.get("evidence")
    if not isinstance(evidence_rows, list):
        raise ValueError("reproduction config requires an evidence list")
    evidence_records = tuple(_parse_evidence(row) for row in evidence_rows)
    evidence = EvidenceRegistry(evidence_records).freeze()
    baseline = config.get("baseline", [])
    followup = config.get("followup", [])
    if not isinstance(baseline, list) or not isinstance(followup, list):
        raise ValueError("baseline and followup must be lists")
    if len(baseline) > MAX_RECORDS or len(followup) > MAX_RECORDS:
        raise ValueError("study rows exceed record cap")
    result = analyze_normal_use(baseline, followup, task_kind=str(config.get("task_kind", "keyed")))
    safe_payload: dict[str, Any] = {"schema_version": "SafeAILiteracyV1", "status": "pending_faculty_evidence", "sequence": []}
    safe_config = config.get("safe_ai_literacy")
    if safe_config is not None:
        if not isinstance(safe_config, dict) or not isinstance(safe_config.get("source"), dict):
            raise ValueError("safe-AI literacy approval must be an object with a source record")
        claim_id = str(safe_config.get("claim_id", ""))
        claims = {record.claim_id: record for record in evidence_records}
        if claim_id not in claims:
            raise ValueError("safe-AI literacy claim is not in the signed evidence registry")
        try:
            source = SourceApproval(**safe_config["source"])
        except TypeError as error:
            raise ValueError(f"invalid safe-AI source approval: {error}") from error
        safe_payload = {
            "schema_version": "SafeAILiteracyV1", "status": "approved_fixed_sequence",
            "sequence": build_safe_ai_literacy_sequence(claims[claim_id], source),
        }
    return config, input_artifacts, novelty, evidence, result, StudyProtocol.default(), safe_payload


def _check_output_cap(directory: Path) -> None:
    total = sum(path.stat().st_size for path in directory.iterdir() if path.is_file())
    if total > MAX_OUTPUT_BYTES:
        raise ValueError("reproduction artifacts exceed output size cap")


def reproduce_study(config_path: Path, output_dir: Path) -> dict[str, Any]:
    config_path = Path(config_path).resolve()
    output_dir = Path(output_dir).resolve()
    config, input_artifacts, novelty, evidence, result, protocol, safe_payload = _prepare(config_path, output_dir)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.", suffix=".partial", dir=output_dir.parent))
    try:
        row_sources = {
            "eligible_pairs": "unique frozen baseline learner/task pairs",
            "baseline_count": "frozen baseline rows", "followup_count": "frozen follow-up rows",
            "matched_followups": "learner/task match over frozen rows",
            "unmatched_followups": "frozen follow-up pairs absent from baseline",
            "attrition_count": "eligible baseline pairs absent from follow-up",
        }
        metric_sources = {key: "frozen matched follow-up rows" for key in result.metrics}
        citation_sources = {
            str(row["claim_id"]): f"{row['identifier']} at {row['source_location']}"
            for row in evidence["records"]
        }
        write_json_atomic(temporary / "tables.json", {
            "schema_version": "AdaptTablesV1", "result": result.to_dict(),
            "number_sources": {**row_sources, **metric_sources}, "citation_sources": citation_sources,
        })
        write_json_atomic(temporary / "figure.json", {
            "schema_version": "AdaptFigureV1", "status": result.status,
            "series": [{"name": key, "value": value, "source": "tables.json"} for key, value in result.metrics.items()],
        })
        write_json_atomic(temporary / "manuscript-inputs.json", {
            "schema_version": "ManuscriptInputsV1", "novelty_registry": novelty,
            "evidence_registry": evidence, "protocol": {
                "version": protocol.protocol_version, "design": protocol.design,
                "analysis_plan": protocol.analysis_plan, "withdrawal_policy": protocol.withdrawal_policy,
            }, "result": result.to_dict(),
        })
        snapshot_artifacts = {
            **input_artifacts,
            "analysis_code_research": Path(research_module.__file__).resolve(),
            "analysis_code_reproduce": Path(__file__).resolve(),
            "table": temporary / "tables.json", "figure": temporary / "figure.json",
            "result": temporary / "tables.json", "manuscript_inputs": temporary / "manuscript-inputs.json",
        }
        snapshot = AnalysisSnapshotV1.freeze(
            _required_text(config, "snapshot_id"), _required_text(config, "protocol_version"),
            _required_text(config, "model_version"), snapshot_artifacts,
        )
        if not snapshot.verify(snapshot_artifacts):
            raise ValueError("frozen snapshot verification failed")
        snapshot_payload = snapshot.to_dict()
        snapshot_payload["novelty_registry_sha256"] = novelty["sha256"]
        snapshot_payload["evidence_registry_sha256"] = evidence["sha256"]
        write_json_atomic(temporary / "snapshot.json", snapshot_payload)
        _write_text_atomic(temporary / "manuscript.md", render_manuscript(snapshot, evidence, result, novelty=novelty))
        _write_text_atomic(
            temporary / "supplement.md",
            "# Supplement\n\n## Frozen novelty registry\n\n```json\n"
            + json.dumps(novelty, indent=2, sort_keys=True) + "\n```\n\n## Evidence registry\n\n```json\n"
            + json.dumps(evidence, indent=2, sort_keys=True) + "\n```\n",
        )
        _write_text_atomic(temporary / "dossier.md", render_institutional_dossier(protocol))
        write_json_atomic(temporary / "safe-ai-literacy.json", safe_payload)
        _check_output_cap(temporary)
        output_names = (
            "snapshot.json", "tables.json", "figure.json", "manuscript.md", "supplement.md",
            "dossier.md", "safe-ai-literacy.json", "manuscript-inputs.json",
        )
        output_hashes = {name: sha256_file(temporary / name) for name in output_names}
        manifest: dict[str, Any] = {
            "schema_version": "ReproductionManifestV1",
            "command": "pathlab-adapt reproduce-study --config <frozen-config> --output-dir <new-directory>",
            "status": result.status, "snapshot_sha256": snapshot.snapshot_sha256,
            "output_hashes": output_hashes,
            "verified": all(sha256_file(temporary / name) == digest for name, digest in output_hashes.items()),
            "submission_performed": False,
        }
        write_json_atomic(temporary / "reproduction-manifest.json", manifest)
        _check_output_cap(temporary)
        temporary.replace(output_dir)
        return manifest
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
