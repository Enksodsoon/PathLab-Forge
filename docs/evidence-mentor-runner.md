# Evidence Mentor runner

`EvidenceMentorRunner` is a standalone per-user process. It binds only to
`127.0.0.1`, requires a 256-bit bearer token from `ipc-token`, and owns the
SQLite WAL queue, leases, checkpoints, signing key, and result artifacts.

Install from a versioned Forge distribution:

```powershell
.\scripts\evidence-mentor-task.ps1 -RuntimeRoot 'C:\Path\To\pathlab-forge'
```

Default state is `D:\PathLabData\EvidenceMentor\state` when the data drive is
present, otherwise `%LOCALAPPDATA%\PathLab\EvidenceMentor\state`. Analysis is
offline. Acquisition is a separate allowlisted operation and must reserve quota before download or
extraction. `GET /v1/status` returns authenticated, non-outcome runtime and quota telemetry. A
dashboard failure does not stop processing.

The deterministic cell/IHC baseline is runnable. The checksum-pinned DINOv2-small worker is locally
executable on the P2000 but remains `not-evaluable` because its cross-tissue held-out cohort is
incomplete. Hibou-B and HoVer-Net remain `not-evaluable` until exact, rights-approved workers and
model artifacts are installed beneath `state/models/<pack-id>/<version>`. External workers use
`pathlab.model-worker-result/1`, inherit the
16 GB RAM and 4.5 GB VRAM envelope, receive offline environment controls, and are rejected on the
P2000 unless they declare CUDA 12.6 and `sm_61`. Forge signs only bounded regions and descriptors;
embeddings, raw pixels and clinical outputs are rejected.

Acquire only the pinned DINOv2 safetensors baseline (never the pickle artifact) with:

```powershell
.\scripts\acquire-dinov2-small.ps1
```

The acquisition command reserves the fixed model quota, downloads from the exact upstream commit,
verifies byte counts and SHA-256 values before atomic installation, and records a local receipt. It
does not activate the pack. Worker installation, runtime qualification, model qualification, and
activation remain separate gates.

H&E jobs additionally require a `pathlab.tile-cache/1` manifest and its SHA-256 in
`tileCacheManifest` and `tileCacheManifestSha256`. Forge verifies the source revision, source/sample
provenance, every 512 x 512 RGB PNG tile, coordinates, and checksums before starting the external
worker. Preview-only H&E model inference is rejected. A bounded, breast-only BRACS contract smoke can
be built idempotently with:

```powershell
.\scripts\build-bracs-dinov2-tile-cache.ps1
```

That cache is real research data but not a qualifying cohort: it lacks the frozen reference, GI,
lung, lymph-node, independent benign/reactive-source, and OOD groups.

New material uses fixed hard buckets: 45 GB source, 25 GB derived, 10 GB models, 10 GB evidence/test,
and a 10 GB untouchable reserve. Existing BRACS remains grandfathered read-only and is not copied.

Atlas-H&E remains a Training Lab research track. Its four-hour probe may continue locally only when
resource limits hold, no OOM occurs, validation improves, and projected completion is within seven
days. This runner performs no cloud provisioning or spending.

Rollback removes the scheduled task but preserves queue, evidence, and keys:

```powershell
.\scripts\evidence-mentor-task.ps1 -RuntimeRoot 'C:\Path\To\pathlab-forge' -Uninstall
```
