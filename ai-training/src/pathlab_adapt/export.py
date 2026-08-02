"""Optional atomic ONNX int8 browser export."""

from __future__ import annotations

import importlib.metadata
from pathlib import Path
from typing import Any

from .io import sha256_file


def validate_export_paths(
    output: Path, metadata_output: Path, *, model_source: Path | None = None
) -> None:
    paths = [
        output.resolve(),
        metadata_output.resolve(),
        output.resolve().with_name(f".{output.name}.float.partial"),
        output.resolve().with_name(f".{output.name}.int8.partial"),
    ]
    if model_source is not None:
        paths.append(model_source.resolve())
    if len(set(paths)) != len(paths):
        raise ValueError("model, metadata, and staging paths must be distinct")


def export_onnx_int8(
    model: Any,
    sample_tokens: Any,
    output: Path,
    *,
    opset_version: int = 18,
    metadata_output: Path | None = None,
) -> dict[str, Any]:
    """Export and dynamically quantize a model, never exposing a partial artifact."""

    validate_export_paths(
        output,
        metadata_output or output.with_name(f"{output.name}.metadata.json"),
    )
    try:
        import torch
        from onnxruntime.quantization import QuantType, quantize_dynamic
    except ImportError as error:
        raise RuntimeError(
            "ONNX export is optional; install pathlab-ai-data[adapt-export]"
        ) from error
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    float_partial = output.with_name(f".{output.name}.float.partial")
    int8_partial = output.with_name(f".{output.name}.int8.partial")
    try:
        model.eval()
        with torch.no_grad():
            torch.onnx.export(
                model,
                sample_tokens,
                float_partial,
                input_names=["tokens"],
                output_names=["retention", "effort", "calibration", "source_risk"],
                dynamic_axes={"tokens": {0: "batch", 1: "sequence"}},
                opset_version=opset_version,
            )
        quantize_dynamic(float_partial, int8_partial, weight_type=QuantType.QInt8)
        int8_partial.replace(output)
    finally:
        float_partial.unlink(missing_ok=True)
        int8_partial.unlink(missing_ok=True)
    return {
        "status": "exported_unapproved",
        "format": "onnx",
        "quantization": "dynamic_int8",
        "artifact_sha256": sha256_file(output),
        "artifact_size_bytes": output.stat().st_size,
        "runtime": f"onnxruntime {importlib.metadata.version('onnxruntime')}",
        "opset_version": opset_version,
        "approval_status": "not_approved_until_all_manifest_gates_pass",
    }
