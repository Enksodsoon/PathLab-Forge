"""Behavioral validation job for optional PyTorch/ONNX dependencies."""

from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path
from typing import Any

from .distillation import DistillationConfig, multitask_distillation_loss
from .models import STUDENT_CONFIGS, TRACEFormerConfig, build_trace_former
from .ontology import TRACE_SIM_HEADS


def run_optional_behavior_checks() -> dict[str, Any]:
    checks: dict[str, Any] = {}
    if importlib.util.find_spec("torch") is None:
        checks["torch"] = {"status": "unverified", "reason": "torch_not_installed"}
        checks["onnx_int8_runtime"] = {
            "status": "unverified",
            "reason": "torch_not_installed",
        }
        return {"status": "unverified_missing_dependencies", "checks": checks}

    import torch

    torch.manual_seed(20260802)
    configurations = (TRACEFormerConfig.teacher(), *STUDENT_CONFIGS)
    model_checks: dict[str, Any] = {}
    last_outputs: dict[str, Any] | None = None
    for config in configurations:
        model = build_trace_former(config)
        actual_parameters = sum(parameter.numel() for parameter in model.parameters())
        if config is configurations[0]:
            if not 20_000_000 <= actual_parameters <= 40_000_000:
                raise AssertionError("teacher parameter count is outside 20M-40M")
        elif abs(actual_parameters - config.target_parameters) / config.target_parameters > 0.10:
            raise AssertionError(f"{config.name} parameter count misses its target")
        with torch.no_grad():
            outputs = model(torch.zeros((1, 256), dtype=torch.long))
        if set(outputs) != set(TRACE_SIM_HEADS):
            raise AssertionError("TRACE-Former output heads changed")
        if any(tuple(value.shape) != (1,) for value in outputs.values()):
            raise AssertionError("TRACE-Former head shape changed")
        last_outputs = outputs
        model_checks[config.name] = {
            "status": "verified",
            "actual_parameters": actual_parameters,
            "context_length": 256,
            "head_shapes": {name: list(value.shape) for name, value in outputs.items()},
        }
        del model
    assert last_outputs is not None
    hard_targets = {name: torch.zeros_like(value) for name, value in last_outputs.items()}
    loss = multitask_distillation_loss(
        last_outputs, last_outputs, hard_targets, DistillationConfig()
    )
    if not bool(torch.isfinite(loss)):
        raise AssertionError("distillation loss is not finite")
    checks["torch"] = {
        "status": "verified",
        "models": model_checks,
        "distillation_loss": float(loss.detach()),
    }

    if (
        importlib.util.find_spec("onnx") is None
        or importlib.util.find_spec("onnxruntime") is None
    ):
        checks["onnx_int8_runtime"] = {
            "status": "unverified",
            "reason": "onnx_or_onnxruntime_not_installed",
        }
        return {"status": "unverified_missing_dependencies", "checks": checks}

    import onnxruntime

    from .export import export_onnx_int8

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        model = build_trace_former(STUDENT_CONFIGS[0])
        output = root / "student.onnx"
        metadata = root / "student.metadata.json"
        export_record = export_onnx_int8(
            model,
            torch.zeros((1, 256), dtype=torch.long),
            torch.zeros((1, 256, STUDENT_CONFIGS[0].continuous_features), dtype=torch.float32),
            output,
            metadata_output=metadata,
        )
        session = onnxruntime.InferenceSession(
            str(output), providers=["CPUExecutionProvider"]
        )
        runtime_outputs = session.run(
            None, {
                "tokens": torch.zeros((1, 256), dtype=torch.long).numpy(),
                "features": torch.zeros((1, 256, STUDENT_CONFIGS[0].continuous_features), dtype=torch.float32).numpy(),
            }
        )
        if len(runtime_outputs) != len(TRACE_SIM_HEADS):
            raise AssertionError("ONNX runtime did not return exactly five heads")
        checks["onnx_int8_runtime"] = {
            "status": "verified",
            "artifact_sha256": export_record["artifact_sha256"],
            "outputs": len(runtime_outputs),
        }
    return {"status": "verified", "checks": checks}
