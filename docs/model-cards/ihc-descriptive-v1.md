# Deterministic H/DAB descriptive pack

Status: `experimental`. The current CPU implementation provides bounded,
within-image DAB-like area and optical-density summaries, deterministic
optical-density watershed cell masks, marker-aware nuclear descriptors for ER,
PR, and Ki-67, membrane-associated descriptors for HER2, and reviewed-region
descriptors for PD-L1. Its stain separation is not yet independently validated,
and it does not claim calibrated cross-batch intensity or clinical scoring.

Until independent marker-specific fixtures pass, every execution is
qualification-only. Marker-aware outputs remain descriptive research estimates.
PD-L1 requires reviewed compartment geometry, not only a compartment-source
label. Missing geometry must produce generic regional output and
`COMPARTMENT_REVIEW_REQUIRED`.

The pack never emits positive/negative calls, ASCO/CAP categories, TPS, CPS,
diagnosis, prognosis, treatment guidance, or cross-slide cell correspondence.

The executable synthetic protocol is documented in
`docs/evidence/brightfield-qualification-protocol-v1.md`. Synthetic generic-DAB
and fail-closed QC checks cannot replace independent marker-specific fixtures.
