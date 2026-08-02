"""Streaming local adapters for public OULAD and license-gated EdNet data."""

from __future__ import annotations

import csv
import hashlib
import math
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

from .ontology import LearnerEvent

EDNET_EVENT_CAP = 5_000_000


def _pseudonym(source: str, raw_id: str, salt: str) -> str:
    if not salt:
        raise ValueError("pseudonym_salt must be non-empty")
    digest = hashlib.sha256(f"{source}|{salt}|{raw_id}".encode("utf-8")).hexdigest()
    return f"{source}-{digest[:24]}"


def _event_id(source: str, *parts: object) -> str:
    digest = hashlib.sha256("|".join(map(str, (source, *parts))).encode("utf-8")).hexdigest()
    return f"{source}-{digest[:24]}"


def adapt_oulad(student_vle_csv: Path, *, pseudonym_salt: str) -> Iterator[LearnerEvent]:
    """Stream OULAD ``studentVle.csv`` without retaining the table in memory."""

    required = {
        "code_module",
        "code_presentation",
        "id_student",
        "id_site",
        "date",
        "sum_click",
    }
    sequence_by_learner: dict[str, int] = {}
    with student_vle_csv.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        missing = required - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"OULAD CSV missing columns: {sorted(missing)}")
        for row_number, row in enumerate(reader, start=2):
            raw_learner = row["id_student"].strip()
            learner = _pseudonym("oulad", raw_learner, pseudonym_salt)
            sequence = sequence_by_learner.get(learner, 0)
            sequence_by_learner[learner] = sequence + 1
            day = int(row["date"])
            click_count = int(row["sum_click"])
            module = row["code_module"].strip()
            presentation = row["code_presentation"].strip()
            site = row["id_site"].strip()
            yield LearnerEvent(
                event_id=_event_id("oulad", module, presentation, raw_learner, site, day, row_number),
                learner_id=learner,
                timestamp_ms=max(0, day) * 86_400_000,
                sequence_index=sequence,
                task_id=f"{module}:{presentation}:site:{site}",
                concept_id=f"vle-site:{site}",
                action="resource_interaction",
                effort=min(1.0, math.log1p(max(0, click_count)) / math.log(101.0)),
                duration_ms=None,
                source="oulad",
                metadata={
                    "click_count": click_count,
                    "module": module,
                    "presentation": presentation,
                    "source_row": row_number,
                },
            )


@dataclass(frozen=True, slots=True)
class EdNetAdapterConfig:
    root: Path
    event_cap: int = EDNET_EVENT_CAP
    pseudonym_salt: str = "pathlab-adapt-ednet"

    def __post_init__(self) -> None:
        if not 1 <= self.event_cap <= EDNET_EVENT_CAP:
            raise ValueError("event_cap must be between 1 and 5,000,000")
        if not self.pseudonym_salt:
            raise ValueError("pseudonym_salt must be non-empty")


def adapt_ednet(config: EdNetAdapterConfig) -> Iterator[LearnerEvent]:
    """Yield at most five million EdNet KT1-style events in stable file order."""

    emitted = 0
    for path in sorted(config.root.rglob("*.csv"), key=lambda item: item.as_posix()):
        raw_learner = path.stem
        learner = _pseudonym("ednet", raw_learner, config.pseudonym_salt)
        with path.open(encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            required = {"timestamp", "solving_id", "question_id", "user_answer", "elapsed_time"}
            missing = required - set(reader.fieldnames or ())
            if missing:
                raise ValueError(f"EdNet CSV {path.name} missing columns: {sorted(missing)}")
            for sequence, row in enumerate(reader):
                if emitted >= config.event_cap:
                    return
                timestamp = int(row["timestamp"])
                elapsed = max(0, int(float(row["elapsed_time"])))
                question = row["question_id"].strip()
                solving = row["solving_id"].strip()
                yield LearnerEvent(
                    event_id=_event_id("ednet", raw_learner, solving, question, timestamp, sequence),
                    learner_id=learner,
                    timestamp_ms=max(0, timestamp),
                    sequence_index=sequence,
                    task_id=f"ednet-question:{question}",
                    concept_id=f"ednet-question:{question}",
                    action="attempt",
                    effort=min(1.0, elapsed / 120_000.0),
                    duration_ms=elapsed,
                    source="ednet",
                    metadata={
                        "solving_id": solving,
                        "user_answer": row["user_answer"],
                        "relative_source_path": path.relative_to(config.root).as_posix(),
                    },
                )
                emitted += 1
