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

Runner `2.1.1` added fail-closed candidate preflight terminalization: immutable
candidate validation failures become `not_evaluable` rather than blocking the
parent coordinator, while database and filesystem I/O failures still surface.
The runtime starts and reports healthy locally, but the upgrade is not accepted:
this Windows host currently returns system error 2 when creating any outbound
firewall rule, including a control rule for a Windows executable. The installer
now stages replacement rules before removing old rules and restores the prior
runtime configuration on any setup or health failure. Model analysis must not
be activated until an outbound-deny rule is verifiably installed.

Read-only DISM found a healthy component store, while SFC found and repaired
protected-file integrity violations. The active firewall provider still needs a
Windows restart before it can accept new rules. Claims are durably paused with
an empty queue. The one-time SYSTEM task `PathLabEvidenceMentorPostRepair` is
prepared to retry the transactional upgrade two minutes after startup, verify
exact outbound-deny coverage and service acceptance, and resume claims only on
PASS. It records a durable result and removes itself after success; a failure
remains paused and fail-closed.

The product owner deferred that restart on 2026-08-22 so real research work can
continue while the PC stays on. This is an explicit degraded-environment waiver,
not firewall acceptance: new model results remain experimental and cannot enter
the qualified registry, learner evidence, deployment activation, or a scientific
claim until outbound-deny and restart-continuation acceptance pass.

Real GI data acquisition is now active as an interactive-user, resumable task.
It is downloading the immutable Zenodo record 1214456 NCT-CRC H&E reference and
validation archives (12,490,560,932 bytes total), pinned to the official file
sizes and MD5 values under CC BY 4.0. Completion additionally records SHA-256
values and a source ledger. The task enforces the 45 GB new-source quota and
runs independently of Forge, Codex, and the analysis service. The dashboard
status contract exposes only bounded acquisition metadata; analysis remains
credential-free and network-disabled. These patch archives add a real GI
external evaluation source, but do not by themselves prove patient-held-out
qualification when patient identity metadata is unavailable.

Runner `2.1.2` is the next staged package. It adds the bounded acquisition
status feed and dashboard rendering without changing the paused analysis state.
It must not replace the installed runtime until the same transactional firewall
and health checks can succeed; source download completion does not depend on
that service upgrade.

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

The signed `all-rounder-candidate-preflight-20260822-v1` campaign also completed
autonomously with `campaignCompleted=true` and `campaignTargetMet=false`.
DINOv2, Hibou-B, GigaPath, HoVer-Net, PathoSAM, Qwen, and Atlas each reached the
honest terminal status `not_evaluable`; no candidate job or model inference was
launched. The result is an availability/readiness record, not a scientific
qualification. Its reusable staging command is
`scripts/stage-all-rounder-candidate-preflight.ps1`.

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
