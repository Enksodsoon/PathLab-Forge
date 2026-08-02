"""TRACE-Former configurations and optional PyTorch implementations."""

from __future__ import annotations

import importlib.util
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True, slots=True)
class TRACEFormerConfig:
    name: str
    context_length: int
    token_vocabulary: int
    d_model: int
    layers: int
    attention_heads: int
    feedforward_dimension: int
    heads: tuple[str, ...]
    target_parameters: int
    quantization: str = "float32"

    @property
    def estimated_parameters(self) -> int:
        embeddings = (self.token_vocabulary + self.context_length + 8) * self.d_model
        attention_and_ffn = self.layers * (
            4 * self.d_model * self.d_model
            + 2 * self.d_model * self.feedforward_dimension
            + 9 * self.d_model
        )
        outputs = len(self.heads) * (self.d_model + 1)
        return embeddings + attention_and_ffn + outputs

    @classmethod
    def teacher(cls) -> TRACEFormerConfig:
        return cls(
            name="trace-former-teacher-32m",
            context_length=256,
            token_vocabulary=56_000,
            d_model=384,
            layers=6,
            attention_heads=8,
            feedforward_dimension=1_536,
            heads=("retention", "effort", "calibration", "source_risk"),
            target_parameters=32_000_000,
        )


STUDENT_CONFIGS = (
    TRACEFormerConfig(
        "trace-student-3m-int8", 256, 20_000, 128, 3, 4, 384,
        ("retention", "effort", "calibration", "source_risk"), 3_000_000, "int8"
    ),
    TRACEFormerConfig(
        "trace-student-8m-int8", 256, 32_000, 192, 4, 6, 768,
        ("retention", "effort", "calibration", "source_risk"), 8_000_000, "int8"
    ),
    TRACEFormerConfig(
        "trace-student-15m-int8", 256, 43_000, 256, 5, 8, 1_024,
        ("retention", "effort", "calibration", "source_risk"), 15_000_000, "int8"
    ),
)


def optional_torch_available() -> bool:
    return importlib.util.find_spec("torch") is not None


def build_trace_former(config: TRACEFormerConfig) -> Any:
    """Construct the multitask model only when the optional runtime is installed."""

    if not optional_torch_available():
        raise RuntimeError("PyTorch is optional; install pathlab-ai-data[adapt-model]")
    import torch
    from torch import nn

    class TRACEFormer(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.config = config
            self.token_embedding = nn.Embedding(config.token_vocabulary, config.d_model)
            self.position_embedding = nn.Embedding(config.context_length, config.d_model)
            layer = nn.TransformerEncoderLayer(
                d_model=config.d_model,
                nhead=config.attention_heads,
                dim_feedforward=config.feedforward_dimension,
                batch_first=True,
                norm_first=True,
            )
            self.encoder = nn.TransformerEncoder(layer, num_layers=config.layers)
            self.output_heads = nn.ModuleDict(
                {name: nn.Linear(config.d_model, 1) for name in config.heads}
            )

        def forward(self, tokens: Any, padding_mask: Any | None = None) -> dict[str, Any]:
            if tokens.shape[1] > config.context_length:
                raise ValueError(f"context exceeds {config.context_length} events")
            positions = torch.arange(tokens.shape[1], device=tokens.device).unsqueeze(0)
            values = self.token_embedding(tokens) + self.position_embedding(positions)
            encoded = self.encoder(values, src_key_padding_mask=padding_mask)
            final = encoded[:, -1, :]
            return {name: head(final).squeeze(-1) for name, head in self.output_heads.items()}

    return TRACEFormer()
