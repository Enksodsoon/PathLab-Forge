"""Shared deterministic feature encoding for training and browser parity."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from .ontology import LearnerEvent, TRACE_SIM_HEADS

FEATURE_NAMES = (
    "correct", "effort", "hint_used", "confidence", "source_checked",
    "duration", "gap", "pan_distance", "zoom_reversals", "revisit_count",
    "spatial_error", "missing_fraction",
)


def stable_token(event: LearnerEvent, vocabulary: int) -> int:
    value = f"{event.concept_id}|{event.task_id}|{event.action}".encode()
    return int.from_bytes(hashlib.sha256(value).digest()[:8], "big") % vocabulary


def encode_features(event: LearnerEvent) -> tuple[float, ...]:
    meta = event.metadata
    raw = (
        None if event.correct is None else float(event.correct), event.effort,
        None if event.hint_used is None else float(event.hint_used), event.confidence,
        None if event.source_checked is None else float(event.source_checked),
        None if event.duration_ms is None else min(event.duration_ms / 60_000, 1.0),
        min(float(meta.get("gap_events", 0)) / 64, 1.0),
        min(float(meta.get("pan_distance", 0)) / 4, 1.0),
        min(float(meta.get("zoom_reversals", 0)) / 16, 1.0),
        min(float(meta.get("revisit_count", 0)) / 4, 1.0),
        min(float(meta.get("spatial_error", 0)) / 0.5, 1.0),
    )
    missing = sum(value is None for value in raw) / len(raw)
    return tuple(0.0 if value is None else float(value) for value in raw) + (missing,)


def encode_targets(event: LearnerEvent) -> dict[str, float]:
    values = {
        "retention": event.retention_target, "effort": None if event.effort is None else event.effort >= 0.5,
        "hint_need": event.hint_need_target, "calibration_risk": event.calibration_risk_target,
        "source_risk": event.source_risk_target,
    }
    if any(values[name] is None for name in TRACE_SIM_HEADS):
        raise ValueError("TRACE-SIM training event is missing a prespecified target")
    return {name: float(bool(values[name])) for name in TRACE_SIM_HEADS}


@dataclass(frozen=True, slots=True)
class EncodedWindow:
    tokens: tuple[int, ...]
    features: tuple[tuple[float, ...], ...]
    targets: dict[str, float]
    learner_id: str
    event_id: str


def encode_windows(events: list[LearnerEvent], *, vocabulary: int, context: int = 32, stride: int = 8) -> list[EncodedWindow]:
    by_learner: dict[str, list[LearnerEvent]] = {}
    for event in events:
        by_learner.setdefault(event.learner_id, []).append(event)
    windows: list[EncodedWindow] = []
    for learner_id, rows in sorted(by_learner.items()):
        rows.sort(key=lambda item: item.sequence_index)
        for end in range(1, len(rows), stride):
            chunk = rows[max(0, end - context + 1):end + 1]
            windows.append(EncodedWindow(
                tuple(stable_token(item, vocabulary) for item in chunk),
                tuple(encode_features(item) for item in chunk), encode_targets(rows[end]),
                learner_id, rows[end].event_id,
            ))
    return windows
