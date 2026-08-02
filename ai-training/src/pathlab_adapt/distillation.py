"""Optional multitask knowledge-distillation primitives."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .ontology import TRACE_SIM_HEADS


@dataclass(frozen=True, slots=True)
class DistillationConfig:
    temperature: float = 2.0
    soft_target_weight: float = 0.7
    hard_target_weight: float = 0.3
    seed: int = 20260802

    def __post_init__(self) -> None:
        if self.temperature <= 0:
            raise ValueError("temperature must be positive")
        if self.soft_target_weight < 0 or self.hard_target_weight < 0:
            raise ValueError("distillation weights must be non-negative")
        if abs(self.soft_target_weight + self.hard_target_weight - 1.0) > 1e-9:
            raise ValueError("distillation weights must sum to one")


def multitask_distillation_loss(
    student_outputs: dict[str, Any],
    teacher_outputs: dict[str, Any],
    hard_targets: dict[str, Any],
    config: DistillationConfig,
) -> Any:
    """Calculate frozen-teacher binary distillation loss for every shared head."""

    required_heads = set(TRACE_SIM_HEADS)
    if (
        set(student_outputs) != required_heads
        or set(teacher_outputs) != required_heads
        or set(hard_targets) != required_heads
    ):
        raise ValueError("distillation mappings must contain exactly the five prespecified heads")
    try:
        import torch
        from torch.nn import functional
    except ImportError as error:
        raise RuntimeError("PyTorch is optional; install pathlab-ai-data[adapt-model]") from error
    head_names = sorted(required_heads)
    total = torch.zeros((), device=next(iter(student_outputs.values())).device)
    for name in head_names:
        student = student_outputs[name]
        teacher = teacher_outputs[name].detach()
        soft_targets = torch.sigmoid(teacher / config.temperature)
        soft_loss = functional.binary_cross_entropy_with_logits(
            student / config.temperature, soft_targets
        ) * (config.temperature**2)
        hard_loss = functional.binary_cross_entropy_with_logits(
            student, hard_targets[name].to(dtype=student.dtype)
        )
        total = total + config.soft_target_weight * soft_loss + config.hard_target_weight * hard_loss
    return total / len(head_names)
