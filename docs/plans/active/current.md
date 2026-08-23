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

The clean NCT-CRC transfer was restarted after the Zenodo endpoint ignored HTTP
resume and produced an oversized checksum-invalid partial. The invalid file is
preserved under acquisition quarantine and cannot enter the source ledger. The
worker now performs at most three clean attempts, captures the real curl exit
code, rejects size/checksum mismatches, and never requests byte-range resume.

An installed-state audit on 2026-08-23 found and repaired two operational
bookkeeping defects without restarting the service: the dashboard launcher now
passes one-time fragments through an explicit Edge/Chrome process, and the
active-version marker now agrees with the running 2.1.1 XML, endpoint, and Java
child. The hidden SYSTEM post-reboot task was restored and verified `Ready` by
an elevated repair receipt. Future failed upgrades derive rollback identity from
the actual prior XML rather than a possibly stale marker. Runtime 2.1.2 is built
and locally tested but remains uninstalled until outbound firewall creation can
pass after the deferred reboot.

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

On 2026-08-23 the NCT-CRC acquisition reached `completed`: both Zenodo 1214456
archives passed their official MD5 values, independent local SHA-256 hashing,
and source-ledger publication. The bounded
`nct-crc-gi-20-per-class-v1` cohort then materialized 360 deterministic
coordinate-bound samples (20 reference and 20 query patches for each of nine
published tissue classes) without expanding the full archives. Its cohort
manifest SHA-256 is
`e3fb44a4e977aeb99a1a5d0a6a97fcd8495fc14b24e66ac126e8205594bdbb38`.
The public release does not provide a per-patch patient map suitable for an
independent overlap audit, and the other frozen tissue/OOD groups remain
absent, so the full H&E gate remains `NOT_EVALUABLE`.

The autonomous `dinov2-nct-crc-gi-execution-20260823-v1` campaign executed the
pinned DINOv2-small worker against one real checksum-bound GI query patch on
the GPU lane with offline analysis enforced and zero retries. It produced
signed evidence SHA-256
`2ea00e772d27da090e5799fdd11bafe69924ca238dea9190c9e816ad0f4ce2a6`
and a signed terminal `experimental` attestation manifest SHA-256
`f69abdcafc68bdafcf707177a6a2a83a5b61f8c7f100e69d4bcd40b77473c6e3`.
`campaignCompleted=true` and `campaignTargetMet=false`: this is real-data
execution evidence, not cohort retrieval performance or deployment
qualification. The next engineering gate is a cohort worker that loads the
model once and computes the frozen color-histogram comparison, retrieval,
repeatability, and OOD metrics without exporting embeddings.

That cohort worker is now implemented and exercised. Immutable checkpoint names
closed a progress/checkpoint race found by the runner during acceptance, and
campaign `dinov2-nct-crc-gi-retrieval-20260823-v3` subsequently completed all
720 work units (two deterministic passes over 360 samples) on the GPU lane with
zero retry. Signed evidence SHA-256 is
`824621fbe90f23af0cedc7d6038b7c3429177bbdffb448fc33c2c6efc530271c` and the
signed attestation SHA-256 is
`3f63f446f3fde3647681918d699fa141852f0526133290644f1028669d8d40dc`.
The installed 2.1.1 report adapter conservatively records `experimental` and
does not expose the worker's aggregate cohort metrics; the branch now contains
the strict signed-report adapter for the next service package. Full H&E remains
`NOT_EVALUABLE` because patient mapping, breast, lung, lymph-node,
benign/reactive-source, and OOD coverage are still missing.

Service package 2.1.3 is now built and staged with the strict signed aggregate
report adapter. Its generated elevated launcher is
`build/Upgrade-PathLab-EvidenceMentor-2.1.3.ps1`. Activation remains deferred
because the current Windows firewall provider cannot create the required
outbound-deny rules until the previously identified restart prerequisite is
satisfied; the installer does not bypass this fail-closed gate.

The bounded `tcga-luad-lusc-he-20x2-v1` acquisition is now running
autonomously in the interactive acquisition context. Its immutable GDC manifest
contains 40 patient-distinct open-access SVS files: ten normal and ten tumor
slides from TCGA-LUAD for the reference split, and the same counts from
TCGA-LUSC for the query split. The total frozen transfer is 720,405,670 bytes.
Every completed source is checked against the GDC MD5 and then independently
SHA-256 ledgered; the offline service receives no credentials or network
access. After completion, `build-tcga-lung-dinov2-cohort.ps1` selects a
tissue-bearing coordinate from a local overview, extracts an immutable 512 px
tile, and freezes the lung cohort. The 2.1.3-only
`stage-tcga-lung-dinov2-retrieval.ps1` then submits its two-pass qualification
campaign with an explicit cohort path and checksum.
Scheduled task `PathLabTcgaLungCohortBuild` is installed and waiting; it polls
the acquisition status every five minutes and runs that offline build without
Forge, Codex, or the dashboard being open.

CAMELYON lymph-node acquisition remains `NOT_EVALUABLE` and has not started.
The two official CAMELYON17 pages currently expose conflicting reuse language,
so `docs/evidence/lymph-node-source-rights-review-20260823.md` freezes the exact
blocker and required remediation. No gate is lowered and no ambiguous data may
enter evidence, Study Packs, OCI, or either Atlas lineage.
