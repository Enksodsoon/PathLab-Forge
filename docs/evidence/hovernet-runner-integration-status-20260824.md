# HoVer-Net runner integration status — 2026-08-24

## Implemented

The Java external-worker boundary now supports
`pathlab.model-runtime-reference/1` packs without copying or mutating the
existing CUDA runtime. Before process launch it:

- requires the exact shared pack/version path under the configured model root;
- rejects symlink or canonical-path escapes;
- verifies the shared runtime-manifest SHA-256 and every runtime file ledger
  entry;
- verifies the exact candidate path, candidate-ledger SHA-256, and weight
  SHA-256;
- launches the referenced Python executable with the checksum-verified worker;
- forces offline flags, RAM/VRAM limits, and deterministic CuBLAS workspace
  configuration; and
- keeps the existing self-contained DINOv2 executable path backward compatible.

`pathlab.cell-instance-metrics/1` results now receive bounded schema, finite
metric, cohort-hash, boolean-gate, and reason-array validation before they can
reach qualification reporting. Generic HoVer-Net worker failure output is
filtered through the same allowlisted diagnostic boundary as DINOv2.

## Remaining boundary

This source change is not installed in the Windows service and does not yet
cause cell jobs to invoke the external worker. The HoVer-Net worker still
supports only the synthetic runtime-probe request. Cohort loading, atomic
progress/checkpoints, restart resume, exact held-out metrics, and campaign
wiring remain required before a 2.1.7 package can be installed or the
23-patient MoNuSAC campaign can start.

The model remains inactive and `not_evaluable`.
