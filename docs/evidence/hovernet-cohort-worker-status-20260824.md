# HoVer-Net held-out worker integration — 2026-08-24

## Result

`EXPERIMENTAL`, not scientifically qualified and not activated.

Forge now routes checksum-pinned external cell candidates through the durable
GPU worker path. The HoVer-Net adapter supports the frozen 23-patient MoNuSAC
cohort, immutable per-sample checkpoints, exact resume identity validation,
renewable progress sidecars, two-pass deterministic comparison, and bounded
cell-instance qualification metrics. Qualification jobs remain local benchmark
work and cannot publish learner evidence.

## First held-out campaign

Campaign `monusac-hovernet-fast-heldout-20260824-v1` completed autonomously
under installed service `2.1.7`. Its signed report has manifest SHA-256
`71e3de177f69ed0ede38eb3d38496260798fac110f91513d12c87be4ac57c870`.
The complete attestation file has SHA-256
`d3fcd6b58558d5838ac8d5a384573b40427e6a19e376ee4a6ccc03f9b831be00`.

- Macro PQ: `0.3631101775` (required at least `0.45`)
- Instance Dice: `0.3534527081` (required at least `0.70`)
- Count error: `0.6979456676` (required at most `0.15`)
- Median morphometry bias: `0.1407778566` (required at most `0.10`)
- Failed-region rate: `0.1304347826` (required at most `0.05`)
- Deterministic repeat, four-tissue coverage, and resource compliance: passed

`campaignCompleted=true` and `campaignTargetMet=false`. The report also marked
cohort integrity as failed because the first worker collapsed three sample
failures into a count and could not distinguish inference failures from rights
or checksum failures. This is an attribution defect, not evidence that the
rights gate actually failed.

The runner recorded only 22/23 terminal work units because it could miss a
final progress sidecar written immediately before child-process exit. The
scientific result and signed report completed, but the operational display was
misleading.

## Bounded remediation repair

Runner source `2.1.8` performs one final validated sidecar read after successful
worker exit. Pack `cell-hovernet-fast-monusac-v1/7` preserves bounded per-sample
failure records containing only sample ID, organ, and a stable failure code. It
also keeps inference failures separate from rights, checksum, geometry, and
annotation failures. No gate, sample, weight, or preprocessing rule changed.

## Actual-host repair evidence

- Adapter pack: `cell-hovernet-fast-monusac-v1/7`
- Pack SHA-256: `49db0a966d9500e61f109f3196c557f233d1ea58136d38835db315b32c910981`
- Worker SHA-256: `89b1af352df9ac4b573fb9f0dc949db7af3f56f7a3d8c4b5212e744db70d1dd0`
- Runtime-reference SHA-256: `4b9db925098fbd986abeadd4f0692bfcf9036f75edbc5f56748d02bb83934769`
- Probe result SHA-256: `02857aaeab16dc289d7bc1e0397a640d06ed0e989f344fa7e0dcdc7517f08bf3`
- Device/runtime: Quadro P2000, CUDA 12.6, `sm_61`
- Probe output: 72 instances, 532 MiB peak VRAM, 1056.809 MiB peak RAM,
  1.099373 seconds
- Full Gradle tests: passed
- Repository policy verification: passed

The probe is synthetic runtime conformance only. It is not held-out accuracy.

## Remaining gate

The installed Windows service is `2.1.7`. Two elevated `2.1.8` upgrade prompts
were declined, so campaign
`monusac-hovernet-fast-heldout-20260824-v2-remediation` has not been submitted.
After the prepared upgrade is approved, run
`scripts/stage-hovernet-monusac-qualification.ps1`. This is the campaign's one
bounded remediation attempt and uses the unchanged 23-patient cohort and frozen
gates. Its purpose is accurate failure attribution and 23/23 terminal progress;
it is not permission to tune to the test set or lower a gate.

The weight lineage remains CC BY-NC-SA 4.0 restricted private research. It is
not Atlas-Clean eligible and cannot enter OCI or activation through this work.
