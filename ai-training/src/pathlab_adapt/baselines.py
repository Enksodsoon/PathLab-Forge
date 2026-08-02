"""Prespecified comparison baselines for TRACE evaluation."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

BASELINE_NAMES = ("logistic_regression", "bkt", "gru", "ordinary_transformer")


@dataclass(frozen=True, slots=True)
class BaselineResult:
    name: str
    brier: float | None
    auroc: float | None
    status: str

    def __post_init__(self) -> None:
        if self.name not in BASELINE_NAMES:
            raise ValueError(f"unknown baseline: {self.name}")
        if self.status not in {"measured", "unmeasured", "failed"}:
            raise ValueError("baseline status must be measured, unmeasured, or failed")


class BktBaseline:
    """Small Bayesian Knowledge Tracing baseline with frozen parameters."""

    def __init__(
        self,
        learn_probability: float,
        guess_probability: float,
        slip_probability: float,
        initial_mastery: float = 0.2,
    ) -> None:
        for value in (learn_probability, guess_probability, slip_probability, initial_mastery):
            if not 0.0 <= value <= 1.0:
                raise ValueError("BKT probabilities must be in [0, 1]")
        self.learn_probability = learn_probability
        self.guess_probability = guess_probability
        self.slip_probability = slip_probability
        self.mastery = initial_mastery

    def observe(self, correct: bool) -> float:
        prediction = self.mastery * (1 - self.slip_probability) + (1 - self.mastery) * self.guess_probability
        if correct:
            denominator = max(prediction, 1e-12)
            posterior = self.mastery * (1 - self.slip_probability) / denominator
        else:
            denominator = max(1 - prediction, 1e-12)
            posterior = self.mastery * self.slip_probability / denominator
        self.mastery = posterior + (1 - posterior) * self.learn_probability
        return prediction

    def predict_sequence(self, outcomes: tuple[bool, ...]) -> list[float]:
        return [self.observe(outcome) for outcome in outcomes]


class LogisticBaseline:
    """Deterministic dependency-free logistic regression for small feature matrices."""

    def __init__(self, features: int) -> None:
        if features < 1:
            raise ValueError("features must be positive")
        self.weights = [0.0] * features
        self.bias = 0.0

    def fit(
        self,
        rows: list[list[float]],
        targets: list[bool],
        *,
        iterations: int = 200,
        learning_rate: float = 0.05,
    ) -> "LogisticBaseline":
        if not rows or len(rows) != len(targets):
            raise ValueError("rows and targets must have equal non-zero length")
        for _ in range(iterations):
            gradient = [0.0] * len(self.weights)
            bias_gradient = 0.0
            for row, target in zip(rows, targets):
                probability = self.predict(row)
                error = probability - float(target)
                for index, value in enumerate(row):
                    gradient[index] += error * value
                bias_gradient += error
            scale = learning_rate / len(rows)
            self.weights = [weight - scale * value for weight, value in zip(self.weights, gradient)]
            self.bias -= scale * bias_gradient
        return self

    def predict(self, row: list[float]) -> float:
        if len(row) != len(self.weights):
            raise ValueError("feature width mismatch")
        logit = self.bias + sum(weight * value for weight, value in zip(self.weights, row))
        if logit >= 0:
            return 1.0 / (1.0 + math.exp(-logit))
        exp_value = math.exp(logit)
        return exp_value / (1.0 + exp_value)


def build_neural_baseline(name: str, *, vocabulary: int, d_model: int = 128) -> Any:
    """Build the optional GRU or ordinary Transformer baseline."""

    if name not in {"gru", "ordinary_transformer"}:
        raise ValueError("neural baseline must be gru or ordinary_transformer")
    try:
        from torch import nn
    except ImportError as error:
        raise RuntimeError("PyTorch is optional; install pathlab-ai-data[adapt-model]") from error

    class GruBaseline(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.embedding = nn.Embedding(vocabulary, d_model)
            self.recurrent = nn.GRU(d_model, d_model, batch_first=True)
            self.output = nn.Linear(d_model, 1)

        def forward(self, tokens: Any) -> Any:
            values, _ = self.recurrent(self.embedding(tokens))
            return self.output(values[:, -1]).squeeze(-1)

    class TransformerBaseline(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.embedding = nn.Embedding(vocabulary, d_model)
            layer = nn.TransformerEncoderLayer(d_model, nhead=4, batch_first=True)
            self.encoder = nn.TransformerEncoder(layer, num_layers=2)
            self.output = nn.Linear(d_model, 1)

        def forward(self, tokens: Any) -> Any:
            values = self.encoder(self.embedding(tokens))
            return self.output(values[:, -1]).squeeze(-1)

    return GruBaseline() if name == "gru" else TransformerBaseline()
