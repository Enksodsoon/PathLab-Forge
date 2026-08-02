"""Dependency-free calibration and leakage-safe benchmark metrics."""

from __future__ import annotations

import math
import random
from collections import defaultdict
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Prediction:
    learner_id: str
    target: bool
    probability: float

    def __post_init__(self) -> None:
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


def _quantile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("cannot calculate a quantile of no values")
    location = (len(ordered) - 1) * probability
    lower = int(math.floor(location))
    upper = int(math.ceil(location))
    if lower == upper:
        return ordered[lower]
    fraction = location - lower
    return ordered[lower] * (1 - fraction) + ordered[upper] * fraction


def _auroc(rows: list[Prediction]) -> float:
    positives = [item.probability for item in rows if item.target]
    negatives = [item.probability for item in rows if not item.target]
    if not positives or not negatives:
        raise ValueError("AUROC requires both target classes")
    favorable = 0.0
    for positive in positives:
        for negative in negatives:
            favorable += 1.0 if positive > negative else 0.5 if positive == negative else 0.0
    return favorable / (len(positives) * len(negatives))


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
    candidate_brier = evaluate_predictions(candidate).brier
    baseline_brier = evaluate_predictions(baseline).brier
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

    if iterations < 20:
        raise ValueError("bootstrap requires at least 20 iterations")
    if len(candidate) != len(baseline):
        raise ValueError("candidate and baseline predictions must align")
    for left, right in zip(candidate, baseline):
        if left.learner_id != right.learner_id or left.target != right.target:
            raise ValueError("candidate and baseline learner/target rows must align")
    candidate_by_learner: dict[str, list[Prediction]] = defaultdict(list)
    baseline_by_learner: dict[str, list[Prediction]] = defaultdict(list)
    for left, right in zip(candidate, baseline):
        candidate_by_learner[left.learner_id].append(left)
        baseline_by_learner[right.learner_id].append(right)
    learners = sorted(candidate_by_learner)
    if len(learners) < 2:
        raise ValueError("learner bootstrap requires at least two learners")
    rng = random.Random(seed)
    samples: list[float] = []
    for _ in range(iterations):
        selected = [rng.choice(learners) for _ in learners]
        candidate_sample = [row for learner in selected for row in candidate_by_learner[learner]]
        baseline_sample = [row for learner in selected for row in baseline_by_learner[learner]]
        samples.append(_relative_brier(candidate_sample, baseline_sample))
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
            calibrated.append(Prediction(item.learner_id, item.target, probability))
        return calibrated
