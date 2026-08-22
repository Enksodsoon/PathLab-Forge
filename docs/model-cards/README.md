# Evidence Mentor model qualification

All model packs are private research/education features and default off. A manifest is not proof
that its artifact is installed, licensed, qualified, or publishable. Exact artifact SHA-256 values,
source revisions, rights review, held-out evaluation, deterministic self-test, and P2000 acceptance
must be recorded before a status can move from `not-evaluable`.

| Track | Candidate | Current status | Activation rule |
|---|---|---|---|
| H&E | DINOv2-small | `not-evaluable` | Experimental only after exact artifact and held-out retrieval run |
| H&E | Hibou-B | `not-evaluable` | User accepts gated terms; then rights, reproducibility, quality and latency gates |
| Cells | HoVer-Net fast | `not-evaluable` | Exact private weights plus PQ/count/morphometry/resource gates |
| Cells | OD watershed | `experimental` | Deterministic fallback; no clinical cell identity |
| IHC | H/DAB descriptors | `experimental` | Within-slide research descriptors; reviewed compartments for PD-L1 |
| Tutor | Qwen3-0.6B INT4 | `not-evaluable` | Complete browser asset at most 500 MiB and claim-ID-only tests |
| Distillation | Atlas-H&E ~72M | `not-evaluable` | Baselines frozen, derivative rights pass, bounded feasibility run succeeds |

GigaPath remains benchmark-only and cannot contribute evidence to the OCI pilot.
