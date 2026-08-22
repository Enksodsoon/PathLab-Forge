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

Gates 1-4 remain implemented. Service `2.1.0` was installed side-by-side on
2026-08-22 and passed the 19-check non-reboot acceptance, including a real
offline DINOv2 CUDA job on the P2000 across a service restart. An existing
byte-identical runtime is reused; a mismatch fails before the active
configuration changes. The manual reboot-continuation acceptance is still
deferred, so Gate 5 and activation remain incomplete.

The branch now implements `pathlab.qualification-campaign/1` as a durable
parent coordinator above the existing GPU and CPU/I/O lanes. It records frozen
dependencies, attempts, signed attestations, completion versus target success,
and a signed capability matrix. One bounded remediation is enforced. Candidate
manifests cannot activate themselves: v2 execution requires an exact trusted
registry entry binding pack, artifacts, protocol, and qualification attestation.
Evidence and qualification signatures are verified against ACL-protected local
trust registries rather than payload-embedded keys.

Gate 6 now includes executable deterministic baselines for true optical-density
watershed instance masks and morphometry, marker-aware nuclear ER/PR/Ki-67
descriptors, HER2 membrane descriptors, PD-L1 reviewed-compartment enforcement,
generic H/DAB output, PAS/PAS-D, trichrome, GMS, AFB, and Papanicolaou
descriptors. The four deterministic v2 candidate manifests are pinned to
pre-registered protocol files. Evidence v2 and signed multi-pack evidence-set
fusion preserve coordinates, QC, provenance, and uncertainty and explicitly
forbid serial-section cell matching. These implementations are not qualified:
rights-cleared held-out reference sets and the frozen quantitative gates still
must run. Gated DINOv2/Hibou/GigaPath, HoVer-Net/PathoSAM, Qwen assets, and public
cytology data are not represented as acquired or built when their exact
artifacts or rights are absent.

The checksum-frozen `deterministic-baselines-20260822-v1` campaign completed
autonomously against a grandfathered, patient-disjoint BRACS test input. Cell,
IHC, special-stain, and cytology execution tracks each produced a signed
`experimental` attestation. `campaignCompleted=true` and
`campaignTargetMet=false`: this proves durable execution and signing, not the
pre-registered held-out quality gates. The reusable staging command is
`scripts/stage-local-deterministic-campaign.ps1`.

Gate 7 has an executable four-hour feasibility decision contract using
checkpointed 15-20 minute segments, fixed RAM/VRAM limits, validation
improvement, seven-day projection, 24 GiB rental minimum, and USD 100 ceiling.
No Atlas weights have been trained. The campaign must return
`LOCAL_INSUFFICIENT`/`not_evaluable` or pause for an exact user-approved quote
when lawful teachers, data, checkpoints, or hardware evidence are missing.

Implementation, service installation, model qualification, merge, deployment,
feature activation, and any future clinical/capacity study are separate gates.
Feature flags remain default-off and no diagnostic or clinical-scoring claims
are permitted.
