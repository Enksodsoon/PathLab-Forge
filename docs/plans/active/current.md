# Active Task — Ultra-Fast Stable Local Pipeline

Supersedes completed F1.1 bootstrap scope by explicit product-owner approval.

## Goal

Rebuild import → inspect → render → OME/DZI → `.plslide` for:

- package-ready at or below 3m30s on reference slide;
- process-tree RAM at or below 5.5 GB on Windows 11, 8 GB RAM, 6 cores;
- exact geometry/calibration and hard image-quality gates;
- crash-safe checkpoint resume;
- retained artifact at or below 1.6 GB and peak workspace at or below 3.5 GB.

## Ordered work

1. Single-owner data root, SQLite WAL migration, runtime profile, telemetry.
2. Snapshot/digest separation, persisted inspection, bounded ReaderSession, ETags.
3. Maximum-five-worker exporter and direct final pyramidal OME assembly.
4. Single-pass ledger, canonical package/index, package-backed DZI, safe cleanup.
5. Checkpoints, child-tree containment, RAM/disk governor, fault injection.
6. Reference benchmark and deterministic tile-engine fallback if any hard gate fails.
7. Windows 8 GB/6-core certification, quality gates, soak, evidence report.

Viewer upload, merge, deployment, licensed runtime redistribution, and legacy payload
deletion remain out of scope.
