"""Canonical, content-agnostic learner-event ontology for ADAPT research."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any

CONTROL_ACTIONS = (
    "continue",
    "retrieve",
    "schedule_review",
    "offer_hint",
    "ask_confidence",
    "ask_source_check",
    "pause",
)

TRACE_SIM_HEADS = (
    "retention",
    "effort",
    "hint_need",
    "calibration_risk",
    "source_risk",
)


@dataclass(frozen=True, slots=True)
class LearnerEvent:
    """One pseudonymous, ordered educational interaction.

    The ontology carries task identifiers and learner signals only. It contains
    no slide pixels, medical answer keys, diagnosis, or treatment fields.
    """

    event_id: str
    learner_id: str
    timestamp_ms: int
    sequence_index: int
    task_id: str
    concept_id: str
    action: str
    source: str
    sequence_id: str = ""
    correct: bool | None = None
    effort: float | None = None
    hint_used: bool | None = None
    confidence: float | None = None
    source_checked: bool | None = None
    retention_target: bool | None = None
    hint_need_target: bool | None = None
    calibration_risk_target: bool | None = None
    source_risk_target: bool | None = None
    duration_ms: int | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        for name in (
            "event_id",
            "learner_id",
            "task_id",
            "concept_id",
            "action",
            "source",
        ):
            if not str(getattr(self, name)).strip():
                raise ValueError(f"{name} must be non-empty")
        if not self.sequence_id:
            object.__setattr__(self, "sequence_id", self.learner_id)
        if self.sequence_index < 0:
            raise ValueError("sequence_index must be non-negative")
        if self.duration_ms is not None and self.duration_ms < 0:
            raise ValueError("duration_ms must be non-negative")
        for name in ("effort", "confidence"):
            value = getattr(self, name)
            if value is not None and not 0.0 <= value <= 1.0:
                raise ValueError(f"{name} must be in [0, 1]")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)
