"""Deterministic learner-disjoint and time-forward evaluation protocols."""

from __future__ import annotations

import random
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass

from .ontology import LearnerEvent


def _sort_key(item: LearnerEvent) -> tuple[str, int, int, str]:
    return (item.learner_id, item.timestamp_ms, item.sequence_index, item.event_id)


@dataclass(frozen=True, slots=True)
class EventSplit:
    train: tuple[LearnerEvent, ...]
    validation: tuple[LearnerEvent, ...]
    test: tuple[LearnerEvent, ...]
    protocol: str
    seed: int

    def as_dict(self) -> dict[str, tuple[LearnerEvent, ...]]:
        return {"train": self.train, "validation": self.validation, "test": self.test}

    def assert_no_duplicate_events(self) -> None:
        groups = [set(item.event_id for item in values) for values in self.as_dict().values()]
        if groups[0] & groups[1] or groups[0] & groups[2] or groups[1] & groups[2]:
            raise ValueError("event leakage detected across splits")


def learner_disjoint_split(
    events: Iterable[LearnerEvent],
    *,
    seed: int = 20260802,
    validation_fraction: float = 0.15,
    test_fraction: float = 0.15,
) -> EventSplit:
    """Assign complete learners to exactly one partition."""

    if validation_fraction <= 0 or test_fraction <= 0 or validation_fraction + test_fraction >= 1:
        raise ValueError("fractions must be positive and leave a non-empty training fraction")
    rows = sorted(events, key=_sort_key)
    learners = sorted({item.learner_id for item in rows})
    if len(learners) < 3:
        raise ValueError("learner-disjoint split requires at least three learners")
    random.Random(seed).shuffle(learners)
    validation_count = max(1, round(len(learners) * validation_fraction))
    test_count = max(1, round(len(learners) * test_fraction))
    if validation_count + test_count >= len(learners):
        raise ValueError("not enough learners for non-empty disjoint partitions")
    validation = set(learners[:validation_count])
    test = set(learners[validation_count : validation_count + test_count])
    train = set(learners[validation_count + test_count :])
    result = EventSplit(
        train=tuple(item for item in rows if item.learner_id in train),
        validation=tuple(item for item in rows if item.learner_id in validation),
        test=tuple(item for item in rows if item.learner_id in test),
        protocol="learner_disjoint",
        seed=seed,
    )
    result.assert_no_duplicate_events()
    learner_groups = [set(item.learner_id for item in values) for values in result.as_dict().values()]
    if learner_groups[0] & learner_groups[1] or learner_groups[0] & learner_groups[2] or learner_groups[1] & learner_groups[2]:
        raise ValueError("learner leakage detected across splits")
    return result


def time_forward_split(
    events: Iterable[LearnerEvent],
    *,
    validation_fraction: float = 0.15,
    test_fraction: float = 0.15,
) -> EventSplit:
    """For every learner, reserve only chronologically later events for evaluation."""

    if validation_fraction <= 0 or test_fraction <= 0 or validation_fraction + test_fraction >= 1:
        raise ValueError("fractions must be positive and leave a training prefix")
    by_learner: dict[str, list[LearnerEvent]] = defaultdict(list)
    for item in events:
        by_learner[item.learner_id].append(item)
    partitions: dict[str, list[LearnerEvent]] = {"train": [], "validation": [], "test": []}
    for learner, values in sorted(by_learner.items()):
        ordered = sorted(values, key=lambda item: (item.timestamp_ms, item.sequence_index, item.event_id))
        if len(ordered) < 3:
            raise ValueError(f"time-forward split requires at least three events for {learner}")
        validation_count = max(1, int(len(ordered) * validation_fraction))
        test_count = max(1, int(len(ordered) * test_fraction))
        train_end = len(ordered) - validation_count - test_count
        if train_end < 1:
            raise ValueError(f"not enough events for a training prefix for {learner}")
        partitions["train"].extend(ordered[:train_end])
        partitions["validation"].extend(ordered[train_end : train_end + validation_count])
        partitions["test"].extend(ordered[train_end + validation_count :])
    result = EventSplit(
        train=tuple(sorted(partitions["train"], key=_sort_key)),
        validation=tuple(sorted(partitions["validation"], key=_sort_key)),
        test=tuple(sorted(partitions["test"], key=_sort_key)),
        protocol="time_forward",
        seed=0,
    )
    result.assert_no_duplicate_events()
    return result
