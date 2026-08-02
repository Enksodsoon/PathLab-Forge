"""Deterministic synthetic sequences for recovery and software validation only."""

from __future__ import annotations

import hashlib
import math
import random
from collections.abc import Iterator
from dataclasses import dataclass

from .ontology import LearnerEvent


@dataclass(frozen=True, slots=True)
class SyntheticConfig:
    seed: int = 20260802
    learners: int = 32
    events_per_learner: int = 128
    concepts: int = 8

    def __post_init__(self) -> None:
        if self.learners < 1 or self.events_per_learner < 1 or self.concepts < 1:
            raise ValueError("learners, events_per_learner, and concepts must be positive")


def _stable_id(*parts: object) -> str:
    return hashlib.sha256("|".join(map(str, parts)).encode("utf-8")).hexdigest()[:24]


def generate_synthetic_events(config: SyntheticConfig) -> Iterator[LearnerEvent]:
    """Yield bounded, repeatable mastery/forgetting and metacognitive signals.

    These events describe no humans and cannot support a human-benefit claim.
    """

    for learner_index in range(config.learners):
        learner_seed = int(_stable_id(config.seed, learner_index), 16)
        rng = random.Random(learner_seed)
        learner_id = f"synthetic-{_stable_id(config.seed, 'learner', learner_index)}"
        mastery = [0.15 + 0.25 * rng.random() for _ in range(config.concepts)]
        last_seen = [-1] * config.concepts
        base_time = 1_700_000_000_000 + learner_index * 10_000_000
        for index in range(config.events_per_learner):
            concept = (index * 5 + learner_index) % config.concepts
            gap = index - last_seen[concept] if last_seen[concept] >= 0 else index + 1
            retained = mastery[concept] * math.exp(-0.035 * gap)
            effort = min(1.0, max(0.0, 0.9 - retained + rng.uniform(-0.12, 0.12)))
            hint_used = rng.random() < min(0.85, 0.08 + effort * 0.65)
            source_checked = index % 7 == learner_index % 7 or (
                effort > 0.72 and rng.random() < 0.4
            )
            confidence = min(
                1.0,
                max(0.0, retained + (0.10 if hint_used else 0.0) + rng.uniform(-0.15, 0.15)),
            )
            probability_correct = min(
                0.98,
                max(0.02, retained + (0.12 if hint_used else 0.0)),
            )
            correct = rng.random() < probability_correct
            mastery[concept] = min(
                0.995,
                retained + (0.13 if correct else 0.045) + (0.03 if source_checked else 0.0),
            )
            last_seen[concept] = index
            timestamp = base_time + index * 60_000 + rng.randrange(0, 10_000)
            yield LearnerEvent(
                event_id=f"syn-{_stable_id(config.seed, learner_index, index)}",
                learner_id=learner_id,
                timestamp_ms=timestamp,
                sequence_index=index,
                task_id=f"synthetic-task-{concept}-{index % 5}",
                concept_id=f"synthetic-concept-{concept}",
                action="attempt",
                correct=correct,
                effort=round(effort, 8),
                hint_used=hint_used,
                confidence=round(confidence, 8),
                source_checked=source_checked,
                retention_target=retained >= 0.5,
                duration_ms=int(4_000 + effort * 26_000),
                source="synthetic",
                metadata={
                    "schema_version": "pathlab-adapt-event-v1",
                    "validation_scope": "software_and_recovery_only",
                },
            )
