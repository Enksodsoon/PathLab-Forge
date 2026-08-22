# DINOv2-small H&E baseline

Status: `not-evaluable-held-out-retrieval-pending`.

The public `facebook/dinov2-small` repository is pinned at
`ed25f3a31f01632728cabb09d1542f84ab7b0056`. Acquisition accepts only the
88,249,960-byte `model.safetensors` artifact with SHA-256
`ae1e99fcefd534ed978cdeb8326f08030c96e28b7a81ffcbc98a857c84d14be1`; the
pickle artifact is excluded. Configuration and preprocessing artifacts are
also checksum-pinned in the pack manifest.

The external feature-pack runtime uses Python 3.12, PyTorch 2.7.1+cu126, and a
compiled Windows launcher. Forge still embeds none of Python, PyTorch, CUDA, or
the weights. A runtime manifest pins the interpreter and critical CUDA/model
files, and the worker rechecks that ledger before loading the model. Network
library entry points and Hugging Face/Transformers online behavior are disabled
during analysis.

On the local Quadro P2000 qualification host (compute capability 6.1, 5,120 MiB
VRAM), the CUDA build exposed `sm_61` and completed a real device operation. A
three-run synthetic protocol smoke produced byte-identical evidence-region
content (`9b018aa1483d3dba15cf5070e64dc4112e6ff4c6751f69cc463e2d0778889c25`).
Cold process wall time was 5.89-6.06 seconds; the bounded 16-tile inference stage
was 0.32-0.36 seconds, peak reserved VRAM was 122 MiB, and peak process RAM was
759.37-760.41 MiB. Tampered artifact metadata, a missing offline flag, and an
enabled analysis-network state all failed with no result artifact.

These are runtime and protocol measurements on synthetic tissue-like pixels,
not pathology retrieval evidence. Patient/source-held-out breast, GI, lung,
lymph-node, benign/reactive, OOD, restart, and five-minute refinement gates have
not run. The model pack therefore remains `not-evaluable`, default-off, and
ineligible for Viewer evidence or staff/demo activation. Embeddings remain local
and only bounded evidence regions may leave Forge after model qualification.
