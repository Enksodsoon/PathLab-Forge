# HoVer-Net held-out worker integration — 2026-08-24

## Result

`SOURCE_READY`, not scientifically qualified and not activated.

Forge now routes checksum-pinned external cell candidates through the durable
GPU worker path. The HoVer-Net adapter supports the frozen 23-patient MoNuSAC
cohort, immutable per-sample checkpoints, exact resume identity validation,
renewable progress sidecars, two-pass deterministic comparison, and bounded
cell-instance qualification metrics. Qualification jobs remain local benchmark
work and cannot publish learner evidence.

## Actual-host evidence

- Adapter pack: `cell-hovernet-fast-monusac-v1/6`
- Pack SHA-256: `6e3ff003467786a088737217e561ab68dd7f398574ac3bcb0a7e28e0136a59ea`
- Worker SHA-256: `aa7ae11ad77887bb0f9afb84b8c3ee4b8fd1dd7c8421db0b20c6fdd81e31d320`
- Runtime-reference SHA-256: `4b9db925098fbd986abeadd4f0692bfcf9036f75edbc5f56748d02bb83934769`
- Probe result SHA-256: `77df7c20a5433871bc87d1f1f39f7f626cb9ea7afc1dbb8b9f7d7a54362beb8d`
- Device/runtime: Quadro P2000, CUDA 12.6, `sm_61`
- Probe output: 72 instances, 532 MiB peak VRAM, 1056.012 MiB peak RAM,
  1.143045 seconds
- Full Gradle tests: passed
- Repository policy verification: passed

The probe is synthetic runtime conformance only. It is not held-out accuracy.

## Remaining gate

The installed Windows service is still `2.1.5`. The `2.1.7` elevated upgrade
was offered but not approved during this run, so no held-out HoVer-Net campaign
was submitted. After installation, run
`scripts/stage-hovernet-monusac-qualification.ps1`. The autonomous service will
then expose all verified checkpoints on the dashboard and will sign an honest
`qualified`, `experimental`, or `not_evaluable` result without lowering the
frozen PQ, Dice, count-error, morphometry, failed-region, determinism, rights,
cross-tissue, or resource gates.

The weight lineage remains CC BY-NC-SA 4.0 restricted private research. It is
not Atlas-Clean eligible and cannot enter OCI or activation through this work.
