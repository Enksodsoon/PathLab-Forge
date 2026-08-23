# DINOv2-small H&E baseline

Status: `experimental-executable-baseline`; full qualification remains `not_evaluable`.

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
not run. The model pack therefore remains default-off and ineligible for Viewer
evidence or staff/demo activation. Its executable candidate status is
`experimental`, while the frozen cross-tissue qualification verdict remains
`not_evaluable`. Embeddings remain local
and only bounded evidence regions may leave Forge after model qualification.

## Real BRACS tile-cache smoke

The frozen `bracs-roi-smoke-v1` cache contains seven patient-distinct validation
ROI samples and 28 deterministic 512 x 512 RGB PNG tiles. Its cohort manifest is
19,860,731 bytes including tiles and has SHA-256
`4653fa49c1b488a7ab90f40a7722e802082a02dbeb967303b09078c4b2bbb2bb`.
Every source, sample manifest, tile manifest, tile pixel file, coordinate, and
revision is independently checksum-bound before inference.

One real BRACS ROI worker smoke was repeated three times. Evidence-region content
was identical (`2e4f597caf50837bbf67de8179742a6c233a6160a723f7933adb8e5709860457`).
Cold wall time was 6.88-7.23 seconds, bounded four-tile model time was 0.19-0.23
seconds, peak reserved VRAM was 122 MiB, and peak process RAM was 761.30-762.21
MiB. A stale slide revision and an incorrect tile-manifest checksum both failed
without a result artifact.

This remains a protocol smoke, not held-out retrieval qualification: BRACS ROI is
breast-only, the bounded cache is not a whole-slide cohort, and reference, GI,
lung, lymph-node, independent benign/reactive-source, and OOD coverage are
absent. The pre-registered protocol therefore returns `NOT_EVALUABLE` without
computing or relaxing model-performance thresholds.

## NCT-CRC GI execution cohort

The checksum-verified Zenodo 1214456 archives now provide a deterministic,
bounded GI execution cohort with 20 reference and 20 query patches from each
of the nine published tissue classes. Each selected 224-pixel patch is decoded
from the immutable archive and deterministically materialized as one
coordinate-bound 512-pixel RGB tile. Archive, entry, derived source, sample,
and tile-cache checksums are retained.

This permits real GI execution and later retrieval-metric implementation, but
does not satisfy the pre-registered qualification protocol. The public patch
release does not expose a per-patch patient/slide map suitable for independently
checking patient overlap, and breast, lung, lymph-node, independent
benign/reactive-source, and OOD groups remain absent. These gaps are recorded as
`NOT_EVALUABLE`; no gate is lowered and no deployment eligibility is implied.
