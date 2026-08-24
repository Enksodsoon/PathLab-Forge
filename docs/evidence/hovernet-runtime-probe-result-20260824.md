# HoVer-Net P2000 runtime probe — 2026-08-24

## Result

The checksum-pinned HoVer-Net fast MoNuSAC candidate executed twice on the
actual NVIDIA Quadro P2000 through the shared PyTorch 2.7.1+cu126 runtime. The
analysis process was network-disabled and used a separately pinned SciPy 1.18.1
post-processing overlay.

- Executable pack: `cell-hovernet-fast-monusac-v1/4`
- Pack manifest SHA-256: `aea386cd50251b7c08e4fe92a48b5be48ef0b75d0e2391f2834cead8f7951987`
- Worker SHA-256: `59449aaf04d3d761c5a8fb09c5a88a7ded5bd8adca94aace1a64a614329e4510`
- Runtime-reference SHA-256: `523b5c26abd941d414204df97382a349296a2521f7ec386883ff66597f610953`
- SciPy overlay-manifest SHA-256: `f09197a4218d66adcbcbd080324fbaccaff96e2e3b724ed68b0516d1931f340d`
- Shared runtime copied: no
- Micro-batch: 1
- CUDA / architecture: 12.6 / `sm_61`
- Peak VRAM: 532 MiB in both runs
- Peak RAM: 1050.527 MiB and 1050.402 MiB
- Inference/post-processing time: 1.115667 s and 1.252335 s
- Deterministic instance count: 72 in both runs
- Deterministic research-category counts: `1=0, 2=44, 3=0, 4=20`
- Result SHA-256 values: `bd61d244dc8c54f25a7d7b4c335ff18031fafe8a4345bff5e01a896b89e86365`
  and `82c1714a6b73cfca395115909cf947b4f244b8235e7e2861d038b73b711203e9`

Model storage after the build is 6,271,635,279 bytes of the 10 GiB model quota,
with zero outstanding reservation bytes. Versions 1-3 are preserved as local,
inactive failed build/probe records; version 4 is also inactive.

## Scientific boundary

This used a deterministic synthetic H&E-like image. It proves checkpoint
loading, CUDA execution, tiling, HoVer-Net output decoding, SciPy instance
post-processing, resource measurement, and deterministic descriptive output on
this host. It does not measure instance Dice, PQ, count error, morphometry bias,
cross-tissue behavior, OOD behavior, clinical cell identity, or diagnostic
performance.

The candidate remains `not_evaluable` and cannot activate, publish learner
evidence, or enter an Atlas teacher registry. The next gate is service-integrated
execution against the unchanged 23-patient MoNuSAC held-out protocol.
