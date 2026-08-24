# BRACS breast DINOv2 retrieval result — 2026-08-24

## Result

`EXPERIMENTAL`, not qualified and not activated.

Campaign `dinov2-bracs-breast-retrieval-20260824-v2-engineering-repair`
completed 160/160 GPU work units with zero retry under offline service `2.1.8`.
It evaluated 40 BRACS validation-reference and 40 BRACS test-query ROIs across
breast and benign/reactive groups. The reviewed BRACS split has zero patient
overlap, and every source ROI, derived tile, coordinate, and provenance record
is checksum-bound.

The first campaign attempt completed inference but failed closed before signing
because its track scope did not match the immutable DINOv2 pack scope. A new
preflight assertion rejects that mismatch before submission. The engineering
repair reused the exact cohort and gates; it was not a label-aware scientific
remediation.

## Frozen evidence

- Cohort SHA-256: `c8535d525715e2a9897bf5cceb049333f8045217d2a70fe0b45a0b00718323a3`
- Campaign SHA-256: `6e15eda906df311a896e3827f524d7bfb588e3ad37c0104a7120634118dd7727`
- Signed report manifest: `dbb27032a4e8598d442f02c9208b6a0ed1083b8261a00b37b1b023270fc666e4`
- Complete attestation file SHA-256: `f78fc454ef1e11b83433053b672baa734af4673e8e48aed810cee512eb8e82c5`
- Signed evidence file SHA-256: `44cf468191cdd9c11ac82cb33d44b02f34181a173b50c6c9be68b10dbfa9e7e8`
- Pack SHA-256: `cf28971f3e8aa9c805b83d02d2254b72268603962dd069cca09cda3aaaefac5d`

## Frozen metrics

- Macro Recall@5 improvement over color histogram: `-0.153741497`
  (required at least `0.05`)
- Macro NDCG@10 improvement: `0.005640305`
  (required at least `0.03`)
- Exact ranking repeatability: passed
- Cohort rights and integrity: passed
- Offline bounded execution: passed
- OOD AUROC: `not_evaluable`

`campaignCompleted=true` and `campaignTargetMet=false`. BRACS is a single
source release, so this cohort does not satisfy the source-held-out gate. GI,
lung, lymph-node, and OOD groups are also absent from this standalone campaign.
The baseline-comparison gates failed, so the result is negative even within its
bounded breast/benign retrieval scope. No threshold is lowered, and no model
activation or tuning against this held-out cohort follows.

The dataset remains CC BY-NC 4.0 private-research material and is not an
Atlas-Clean supervision source without a separate derivative-rights decision.
