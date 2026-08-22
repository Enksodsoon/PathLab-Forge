# Deterministic H/DAB descriptive pack

Status: `experimental`. The current CPU implementation provides bounded,
within-image DAB-like area and optical-density summaries plus deterministic
hematoxylin-like connected-component morphometry. It does not yet implement a
validated stain-vector deconvolution, calibrated cross-batch intensity,
marker-specific nuclear counting, HER2 membrane completeness, or PD-L1
tumor/immune compartment measurement.

Until those independent fixtures pass, every execution is qualification-only.
Marker names describe the requested research track; they do not make the generic
measurements marker-specific. PD-L1 requires reviewed compartment geometry, not
only a compartment-source label. Missing geometry must produce generic regional
output and `COMPARTMENT_REVIEW_REQUIRED`.

The pack never emits positive/negative calls, ASCO/CAP categories, TPS, CPS,
diagnosis, prognosis, treatment guidance, or cross-slide cell correspondence.
