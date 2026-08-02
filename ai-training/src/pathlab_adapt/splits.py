"""Deterministic learner-disjoint and time-forward evaluation protocols."""

from __future__ import annotations

import random
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass

from .ontology import LearnerEvent


def _sort_key(item: LearnerEvent) -> tuple[str, int, int, str]:
    return (item.learner_id, item.timestamp_ms, item.sequence_index, item.event_id)


def _assert_unique_event_ids(rows: list[LearnerEvent]) -> None:
    seen: set[str] = set()
    for item in rows:
        if item.event_id in seen:
            raise ValueError(f"duplicate event_id: {item.event_id}")
        seen.add(item.event_id)


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
        groups = [{item.event_id for item in values} for values in self.as_dict().values()]
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
    _assert_unique_event_ids(rows)
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
    learner_groups = [{item.learner_id for item in values} for values in result.as_dict().values()]
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
    rows = list(events)
    _assert_unique_event_ids(rows)
    by_learner: dict[tuple[str, str], list[LearnerEvent]] = defaultdict(list)
    for item in rows:
        by_learner[(item.learner_id, item.sequence_id)].append(item)
    partitions: dict[str, list[LearnerEvent]] = {"train": [], "validation": [], "test": []}
    for learner_key, values in sorted(by_learner.items()):
        learner = ":".join(learner_key)
        ordered = sorted(values, key=lambda item: (item.timestamp_ms, item.sequence_index, item.event_id))
        groups: list[list[LearnerEvent]] = []
        for item in ordered:
            if not groups or groups[-1][0].timestamp_ms != item.timestamp_ms:
                groups.append([])
            groups[-1].append(item)
        if len(groups) < 3:
            raise ValueError(f"time-forward split requires at least three timestamp groups for {learner}")
        validation_groups = max(1, int(len(groups) * validation_fraction))
        test_groups = max(1, int(len(groups) * test_fraction))
        train_end = len(groups) - validation_groups - test_groups
        if train_end < 1:
            raise ValueError(f"not enough timestamp groups for a training prefix for {learner}")
        partitions["train"].extend(item for group in groups[:train_end] for item in group)
        partitions["validation"].extend(item for group in groups[train_end : train_end + validation_groups] for item in group)
        partitions["test"].extend(item for group in groups[train_end + validation_groups :] for item in group)
    result = EventSplit(
        train=tuple(sorted(partitions["train"], key=_sort_key)),
        validation=tuple(sorted(partitions["validation"], key=_sort_key)),
        test=tuple(sorted(partitions["test"], key=_sort_key)),
        protocol="time_forward",
        seed=0,
    )
    result.assert_no_duplicate_events()
    return result
