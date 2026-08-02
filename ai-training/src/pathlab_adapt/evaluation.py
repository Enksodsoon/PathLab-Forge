"""Dependency-free calibration and leakage-safe benchmark metrics."""

from __future__ import annotations

import math
import random
from collections import defaultdict
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Prediction:
    event_id: str
    learner_id: str
    target: bool
    probability: float

    def __post_init__(self) -> None:
        if not self.event_id:
            raise ValueError("event_id must be non-empty")
        if not self.learner_id:
            raise ValueError("learner_id must be non-empty")
        if not 0.0 <= self.probability <= 1.0:
            raise ValueError("probability must be in [0, 1]")


@dataclass(frozen=True, slots=True)
class PredictionMetrics:
    brier: float
    auroc: float
    ece: float
    observations: int


@dataclass(frozen=True, slots=True)
class BootstrapInterval:
    estimate: float
    lower_95: float
    upper_95: float
    iterations: int
    seed: int


MAX_BOOTSTRAP_OPERATIONS = 2_000_000


def _quantile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("cannot calculate a quantile of no values")
    location = (len(ordered) - 1) * probability
    lower = math.floor(location)
    upper = math.ceil(location)
    if lower == upper:
        return ordered[lower]
    fraction = location - lower
    return ordered[lower] * (1 - fraction) + ordered[upper] * fraction


def _auroc(rows: list[Prediction]) -> float:
    positives = sum(item.target for item in rows)
    negatives = len(rows) - positives
    if not positives or not negatives:
        raise ValueError("AUROC requires both target classes")
    ordered = sorted(rows, key=lambda item: item.probability)
    positive_rank_sum = 0.0
    index = 0
    while index < len(ordered):
        end = index + 1
        while end < len(ordered) and ordered[end].probability == ordered[index].probability:
            end += 1
        average_rank = ((index + 1) + end) / 2.0
        positive_rank_sum += average_rank * sum(item.target for item in ordered[index:end])
        index = end
    return (positive_rank_sum - positives * (positives + 1) / 2) / (positives * negatives)


def evaluate_predictions(predictions: list[Prediction] | tuple[Prediction, ...]) -> PredictionMetrics:
    rows = list(predictions)
    if not rows:
        raise ValueError("at least one prediction is required")
    brier = sum((item.probability - float(item.target)) ** 2 for item in rows) / len(rows)
    ece = 0.0
    for index in range(10):
        lower = index / 10
        upper = (index + 1) / 10
        selected = [
            item
            for item in rows
            if (
                lower <= item.probability <= upper
                if index == 9
                else lower <= item.probability < upper
            )
        ]
        if selected:
            mean_probability = sum(item.probability for item in selected) / len(selected)
            mean_target = sum(float(item.target) for item in selected) / len(selected)
            ece += len(selected) / len(rows) * abs(mean_probability - mean_target)
    return PredictionMetrics(brier=brier, auroc=_auroc(rows), ece=ece, observations=len(rows))


def _relative_brier(candidate: list[Prediction], baseline: list[Prediction]) -> float:
    candidate_brier = sum((item.probability - float(item.target)) ** 2 for item in candidate) / len(candidate)
    baseline_brier = sum((item.probability - float(item.target)) ** 2 for item in baseline) / len(baseline)
    if baseline_brier <= 0:
        raise ValueError("baseline Brier score must be positive")
    return (baseline_brier - candidate_brier) / baseline_brier


def bootstrap_relative_brier_improvement(
    candidate: list[Prediction],
    baseline: list[Prediction],
    *,
    iterations: int = 1_000,
    seed: int = 20260802,
) -> BootstrapInterval:
    """Learner-cluster bootstrap of relative Brier improvement."""

    if not 20 <= iterations <= 10_000:
        raise ValueError("bootstrap iterations must be between 20 and 10,000")
    if len(candidate) != len(baseline):
        raise ValueError("candidate and baseline predictions must align")
    for left, right in zip(candidate, baseline):
        if (
            left.event_id != right.event_id
            or left.learner_id != right.learner_id
            or left.target != right.target
        ):
            raise ValueError("candidate and baseline event/learner/target rows must align")
    aggregates: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0, 0.0])
    for left, right in zip(candidate, baseline):
        current = aggregates[left.learner_id]
        current[0] += (left.probability - float(left.target)) ** 2
        current[1] += (right.probability - float(right.target)) ** 2
        current[2] += 1
    learners = sorted(aggregates)
    if len(learners) < 2:
        raise ValueError("learner bootstrap requires at least two learners")
    if len(learners) * iterations > MAX_BOOTSTRAP_OPERATIONS:
        raise ValueError(
            f"bootstrap operation budget exceeded: maximum {MAX_BOOTSTRAP_OPERATIONS:,}"
        )
    rng = random.Random(seed)
    samples: list[float] = []
    for _ in range(iterations):
        candidate_sum = 0.0
        baseline_sum = 0.0
        count = 0.0
        for _learner_index in range(len(learners)):
            current = aggregates[rng.choice(learners)]
            candidate_sum += current[0]
            baseline_sum += current[1]
            count += current[2]
        baseline_brier = baseline_sum / count
        if baseline_brier <= 0:
            raise ValueError("baseline Brier score must be positive")
        samples.append((baseline_brier - candidate_sum / count) / baseline_brier)
    return BootstrapInterval(
        estimate=_relative_brier(candidate, baseline),
        lower_95=_quantile(samples, 0.025),
        upper_95=_quantile(samples, 0.975),
        iterations=iterations,
        seed=seed,
    )


@dataclass(frozen=True, slots=True)
class TemperatureCalibrator:
    temperature: float

    def __post_init__(self) -> None:
        if self.temperature <= 0:
            raise ValueError("temperature must be positive")

    def transform(self, predictions: list[Prediction]) -> list[Prediction]:
        calibrated: list[Prediction] = []
        for item in predictions:
            clipped = min(1 - 1e-7, max(1e-7, item.probability))
            logit = math.log(clipped / (1 - clipped)) / self.temperature
            probability = 1 / (1 + math.exp(-logit))
            calibrated.append(Prediction(item.event_id, item.learner_id, item.target, probability))
        return calibrated
