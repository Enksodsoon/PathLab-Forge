"""Fail-closed OOD and uncertainty controller policy."""

from __future__ import annotations

from dataclasses import dataclass

from .ontology import CONTROL_ACTIONS


@dataclass(frozen=True, slots=True)
class UncertaintySignal:
    ood_score: float
    uncertainty: float

    def __post_init__(self) -> None:
        if not 0.0 <= self.ood_score <= 1.0 or not 0.0 <= self.uncertainty <= 1.0:
            raise ValueError("OOD and uncertainty scores must be in [0, 1]")


@dataclass(frozen=True, slots=True)
class ControllerDecision:
    action: str
    delivery_mode: str
    reason: str

    def __post_init__(self) -> None:
        if self.action not in CONTROL_ACTIONS:
            raise ValueError("action is outside the fixed controller action set")


@dataclass(frozen=True, slots=True)
class ControllerPolicy:
    max_ood_score: float
    max_uncertainty: float

    def decide(self, signal: UncertaintySignal, proposed_action: str = "continue") -> ControllerDecision:
        if proposed_action not in CONTROL_ACTIONS:
            raise ValueError("proposed action is outside the fixed controller action set")
        if signal.ood_score > self.max_ood_score:
            return ControllerDecision("pause", "fixed_order", "out_of_distribution")
        if signal.uncertainty > self.max_uncertainty:
            return ControllerDecision("ask_confidence", "fixed_order", "uncertain")
        return ControllerDecision(proposed_action, "adaptive", "within_prespecified_limits")
