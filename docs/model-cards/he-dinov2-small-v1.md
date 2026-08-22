# DINOv2-small H&E baseline

Status: `not-evaluable-worker-not-installed`.

The public `facebook/dinov2-small` repository is pinned at
`ed25f3a31f01632728cabb09d1542f84ab7b0056`. Acquisition accepts only the
88,249,960-byte `model.safetensors` artifact with SHA-256
`ae1e99fcefd534ed978cdeb8326f08030c96e28b7a81ffcbc98a857c84d14be1`; the
pickle artifact is excluded. Configuration and preprocessing artifacts are
also checksum-pinned in the pack manifest.

The local qualification host reports a Quadro P2000 (compute capability 6.1,
5,120 MiB VRAM). The model files are installed locally, but no worker has yet
passed the required CUDA 12.6 / `sm_61`, offline, memory, latency, restart, or
held-out retrieval gates. Therefore the pack cannot execute or enter Viewer
evidence and remains default-off. Embeddings remain local and only bounded
evidence regions may leave Forge.
