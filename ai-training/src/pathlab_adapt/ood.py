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

    def __post_init__(self) -> None:
        if not 0.0 <= self.max_ood_score <= 1.0:
            raise ValueError("max_ood_score must be in [0, 1]")
        if not 0.0 <= self.max_uncertainty <= 1.0:
            raise ValueError("max_uncertainty must be in [0, 1]")

    def decide(
        self,
        signal: UncertaintySignal,
        proposed_action: str = "continue",
        *,
        approved_manifest: object | None = None,
        approval_attestation: object | None = None,
        approval_authority: object | None = None,
    ) -> ControllerDecision:
        if proposed_action not in CONTROL_ACTIONS:
            raise ValueError("proposed action is outside the fixed controller action set")
        return ControllerDecision("pause", "fixed_order", "fixed_order_only_release")
