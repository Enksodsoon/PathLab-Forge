"""Coverage-driven TRACE-SIM learner simulation.

The simulator creates no medical facts and represents no real learner.  Its only
purpose is to exercise model recovery, safety fallbacks, and product plumbing.
"""

from __future__ import annotations

import hashlib
import math
import random
from collections import Counter
from collections.abc import Iterator, Sequence
from dataclasses import dataclass, replace

from .ontology import LearnerEvent


TIER_EVENTS = {"smoke": 128_000, "integration": 1_280_000, "final": 10_560_000}
SCENARIO_AXES = {
    "mastery": ("novice", "developing", "advanced"),
    "forgetting": ("slow", "fast"),
    "confidence": ("under", "calibrated"),
    "help": ("independent", "hint_seeking"),
    "verification": ("checks", "skips"),
}


def _digest(*parts: object, length: int = 24) -> str:
    return hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()[:length]


def scenario_catalog() -> tuple[dict[str, str], ...]:
    rows: list[dict[str, str]] = []
    for mastery in SCENARIO_AXES["mastery"]:
        for forgetting in SCENARIO_AXES["forgetting"]:
            for confidence in SCENARIO_AXES["confidence"]:
                for help_style in SCENARIO_AXES["help"]:
                    for verification in SCENARIO_AXES["verification"]:
                        rows.append({
                            "mastery": mastery, "forgetting": forgetting,
                            "confidence_style": confidence, "help_style": help_style,
                            "verification_style": verification,
                        })
    return tuple(rows)


@dataclass(frozen=True, slots=True)
class SyntheticSuiteConfig:
    tier: str = "smoke"
    seed: int = 20260802
    event_count: int | None = None
    events_per_learner: int = 256
    concepts: int = 12
    counterfactual_fraction: float = 0.08

    def __post_init__(self) -> None:
        if self.tier not in TIER_EVENTS:
            raise ValueError(f"tier must be one of {sorted(TIER_EVENTS)}")
        if self.events_per_learner < 32 or self.concepts < 2:
            raise ValueError("events_per_learner >= 32 and concepts >= 2 are required")
        if not 0 <= self.counterfactual_fraction <= 0.5:
            raise ValueError("counterfactual_fraction must be in [0, 0.5]")
        if self.event_count is not None and self.event_count < len(scenario_catalog()) * self.events_per_learner:
            raise ValueError("event_count must allocate at least one learner to every scenario family")

    @property
    def events(self) -> int:
        return self.event_count or TIER_EVENTS[self.tier]


def generate_synthetic_suite(config: SyntheticSuiteConfig) -> Iterator[LearnerEvent]:
    scenarios = scenario_catalog()
    learner_count = math.ceil(config.events / config.events_per_learner)
    emitted = 0
    for learner_index in range(learner_count):
        scenario = scenarios[learner_index % len(scenarios)]
        rng = random.Random(int(_digest(config.seed, learner_index), 16))
        learner_id = f"trace-sim-{_digest(config.seed, 'learner', learner_index)}"
        mastery_base = {"novice": 0.22, "developing": 0.52, "advanced": 0.79}[scenario["mastery"]]
        mastery = [max(0.05, min(0.95, mastery_base + rng.uniform(-0.08, 0.08))) for _ in range(config.concepts)]
        last_seen = [-1] * config.concepts
        pending_counterfactual: LearnerEvent | None = None
        for index in range(config.events_per_learner):
            if emitted >= config.events:
                return
            if pending_counterfactual is not None:
                yield pending_counterfactual
                pending_counterfactual = None
                emitted += 1
                continue
            concept = (index * 7 + learner_index * 3) % config.concepts
            gap = index - last_seen[concept] if last_seen[concept] >= 0 else index + 1
            decay = 0.018 if scenario["forgetting"] == "slow" else 0.060
            retained = max(0.01, mastery[concept] * math.exp(-decay * gap))
            effort = max(0.0, min(1.0, 0.92 - retained + rng.uniform(-0.10, 0.10)))
            hint_probability = 0.08 + 0.58 * effort + (0.18 if scenario["help_style"] == "hint_seeking" else -0.04)
            hint_used = rng.random() < max(0.01, min(0.95, hint_probability))
            source_probability = 0.72 if scenario["verification_style"] == "checks" else 0.18
            source_checked = rng.random() < max(0.02, min(0.96, source_probability + 0.18 * effort))
            probability_correct = max(0.02, min(0.98, retained + 0.10 * hint_used + 0.035 * source_checked))
            correct = rng.random() < probability_correct
            confidence_shift = -0.20 if scenario["confidence_style"] == "under" else 0.0
            confidence = max(0.01, min(0.99, probability_correct + confidence_shift + rng.uniform(-0.10, 0.10)))
            calibration_risk = abs(confidence - float(correct)) >= 0.45
            hint_need = retained < 0.48 and not hint_used
            source_risk = (not source_checked) and (confidence >= 0.66 or not correct)
            is_counterfactual = rng.random() < config.counterfactual_fraction and index + 1 < config.events_per_learner and emitted + 1 < config.events
            pair_id = _digest(config.seed, learner_index, index, "pair") if is_counterfactual else ""
            varied_feature = ("confidence", "hint_used", "navigation_effort")[index % 3] if is_counterfactual else ""
            metadata = {
                "schema_version": "pathlab-trace-sim-event-v2",
                "validation_scope": "synthetic_software_recovery_only",
                "scenario": scenario,
                "scenario_id": _digest(*scenario.values(), length=12),
                "gap_events": gap,
                "pan_distance": round(0.15 + effort * 2.4 + rng.random() * 0.2, 6),
                "zoom_reversals": int(effort * 8 + rng.random() * 2),
                "revisit_count": int(gap > config.concepts),
                "spatial_error": round(max(0.0, effort * 0.22 + rng.uniform(-0.03, 0.03)), 6),
                "device_class": ("desktop", "tablet", "phone")[learner_index % 3],
                "pointer_type": ("mouse", "touch", "stylus")[learner_index % 3],
                "counterfactual_pair_id": pair_id,
                "counterfactual_varied_feature": varied_feature,
                "counterfactual_variant": "factual" if is_counterfactual else "none",
            }
            factual = LearnerEvent(
                event_id=f"sim-{_digest(config.seed, learner_index, index)}",
                learner_id=learner_id,
                sequence_id=learner_id,
                timestamp_ms=1_800_000_000_000 + learner_index * 100_000_000 + index * 60_000,
                sequence_index=index,
                task_id=f"sim-task-{concept}-{index % 11}", concept_id=f"sim-concept-{concept}",
                action="attempt", source="synthetic_trace_sim_v2", correct=correct,
                effort=round(effort, 8), hint_used=hint_used, confidence=round(confidence, 8),
                source_checked=source_checked, retention_target=retained >= 0.5,
                hint_need_target=hint_need, calibration_risk_target=calibration_risk,
                source_risk_target=source_risk, duration_ms=int(3_000 + effort * 42_000),
                metadata=metadata,
            )
            yield factual
            if is_counterfactual:
                changed = dict(metadata)
                changed["counterfactual_variant"] = "counterfactual"
                if varied_feature == "confidence":
                    alternate_confidence = round(1.0 - confidence, 8)
                    pending_counterfactual = replace(
                        factual, event_id=f"sim-{_digest(config.seed, learner_index, index, 'cf')}",
                        sequence_index=index + 1, timestamp_ms=factual.timestamp_ms + 1,
                        confidence=alternate_confidence,
                        calibration_risk_target=abs(alternate_confidence - float(correct)) >= 0.45,
                        metadata=changed,
                    )
                elif varied_feature == "hint_used":
                    pending_counterfactual = replace(
                        factual, event_id=f"sim-{_digest(config.seed, learner_index, index, 'cf')}",
                        sequence_index=index + 1, timestamp_ms=factual.timestamp_ms + 1,
                        hint_used=not hint_used,
                        hint_need_target=retained < 0.48 and hint_used,
                        metadata=changed,
                    )
                else:
                    alternate_effort = round(1.0 - effort, 8)
                    changed["pan_distance"] = round(0.15 + alternate_effort * 2.4, 6)
                    pending_counterfactual = replace(
                        factual, event_id=f"sim-{_digest(config.seed, learner_index, index, 'cf')}",
                        sequence_index=index + 1, timestamp_ms=factual.timestamp_ms + 1,
                        effort=alternate_effort, duration_ms=int(3_000 + alternate_effort * 42_000),
                        metadata=changed,
                    )
            mastery[concept] = min(0.995, retained + (0.12 if correct else 0.04) + 0.025 * source_checked)
            last_seen[concept] = index
            emitted += 1


def coverage_report(events: Sequence[LearnerEvent]) -> dict[str, object]:
    scenarios = Counter(str(event.metadata.get("scenario_id", "")) for event in events)
    pairs = Counter(str(event.metadata.get("counterfactual_varied_feature", "")) for event in events)
    missing = sorted({ _digest(*item.values(), length=12) for item in scenario_catalog() } - set(scenarios))
    return {
        "events": len(events), "scenario_families": len(scenarios),
        "expected_scenario_families": len(scenario_catalog()), "missing_scenarios": missing,
        "counterfactual_feature_counts": {key: value for key, value in pairs.items() if key},
        "coverage_complete": not missing and all(pairs[name] > 0 for name in ("confidence", "hint_used", "navigation_effort")),
    }
