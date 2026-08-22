# Active Task — Autonomous Evidence Mentor Service

Supersedes the modular-capability stop rule by explicit product-owner direction
on 2026-08-22. The research, licensing, privacy, and fail-closed boundaries remain.

## Fixed endpoint

Forge submits immutable local-analysis requests to the independently running
`PathLabEvidenceMentor` Windows service. The service owns the WAL queue,
checkpoint recovery, signed evidence packaging, and an operations-only loopback
dashboard. Forge, the dashboard, and Codex may close after a `202 Accepted`
response without interrupting work.

The service uses a dynamic loopback port published through
`pathlab.runner-endpoint/1`, boot-scoped browser sessions, one GPU lane and one
low-priority CPU/I/O lane. Analysis is network-disabled. No raw pixels,
embeddings, patient identifiers, provisional regions, or scientific outcomes
appear in monitoring payloads.

## Delivery gates

1. Queue v2 migration, progress sidecars, renewable leases, retry classes, and pause control.
2. Dynamic endpoint discovery and authenticated standalone dashboard.
3. Forge compact status and one-time dashboard launcher.
4. Checksum-pinned WinSW packaging, ACLs, firewall policy, side-by-side rollback.
5. Actual LocalService, reboot, Session 0 P2000, offline, and recovery acceptance.
6. Independent expert-pack qualification for H&E, cells, IHC, special stains, and cytology.
7. Dual-lineage Atlas feasibility only after lawful frozen teachers exist.

## Current status

Gates 1-4 are implemented on the draft feature branch. The installed 2.0.12
runtime has separate passing `Inspect` and `ServiceRestart` reports proving
live `LocalService`, outbound-deny coverage, P2000 CUDA 12.6 `sm_61` inference,
resource bounds, signed evidence packaging, and restart recovery. Its latest
aggregate report is still `NOT_EVALUABLE` solely because the operator-controlled
manual-reboot challenge has not run. Gate 5 therefore remains incomplete. Gate
6 qualification preparation has begun: ordinary jobs now require a `qualified`
pack, while `experimental` packs are restricted to non-identifying
`qualification-<hex>` jobs. The deterministic cell fallback is accurately
identified as connected-component morphometry, and IHC marker-specific or PD-L1
compartment claims fail closed to generic descriptive output. Neither track is
qualified. A checksum-bound `pathlab.model-qualification-report/1` synthetic
harness now records separated-cell counting, touching-cell separation,
determinism, generic DAB area, relative calibration, and failed-stain refusal
independently. Its current truthful outcome is `experimental`: touching nuclei
are not split, instance masks are unavailable, and marker-specific/PD-L1
fixtures remain `not_evaluable`. Special stains and cytology remain
unimplemented, and Gate 7 has not begun.

Implementation, service installation, model qualification, merge, deployment,
feature activation, and any future clinical/capacity study are separate gates.
Feature flags remain default-off and no diagnostic or clinical-scoring claims
are permitted.
