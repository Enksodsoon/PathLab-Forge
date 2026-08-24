# H&E retrieval qualification protocol v1

Status: pre-registered before cross-tissue evaluation.

This is an internal private-research model qualification protocol. It is not a clinical analytical
procedure, diagnostic validation, ICH Q2(R2) validation, CLSI study, or medical-device performance
claim. Public annotations are research references rather than clinical truth.

## Frozen design

- Evaluation groups: breast, GI, lung, lymph node, and benign/reactive.
- Splits: immutable reference, query, and OOD manifests.
- Patient, slide, and declared source-group overlap across splits: zero.
- Minimum reference samples per group: 20.
- Minimum query samples per group: 20.
- Minimum OOD samples: 20.
- Candidate: checksum-pinned DINOv2-small pack.
- Deterministic comparison: `color-histogram-v1` on exactly the same cached 512-pixel RGB tiles.
- Macro recall@5 improvement over the baseline: at least 0.05 absolute.
- Macro nDCG@10 improvement over the baseline: at least 0.03 absolute.
- OOD AUROC: at least 0.80.
- Exact repeated-run ranking agreement is required.
- Any missing rights, checksum, source grouping, group coverage, or split integrity yields
  `NOT_EVALUABLE`; it cannot be repaired by lowering criteria after results are observed.

These thresholds govern only whether DINOv2-small may be called an experimental executable H&E
retrieval baseline. They do not qualify Hibou-B, any diagnostic task, or activation for staff/demo
accounts. Activation continues to require a separately signed `qualified` pack.
