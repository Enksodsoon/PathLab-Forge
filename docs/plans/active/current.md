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

An elevated 2.1.3 packaging attempt exposed an exact version mismatch: its
authenticated endpoint reported application version 2.1.2. No lung
qualification was launched. The corrected runner, side-by-side runtime, and
launcher are versioned 2.1.4 so installed runtime identity and endpoint version
must agree before campaign submission.

The bounded `tcga-luad-lusc-he-20x2-v1` acquisition completed autonomously in
the interactive acquisition context. Its immutable GDC manifest
contains 40 patient-distinct open-access SVS files: ten normal and ten tumor
slides from TCGA-LUAD for the reference split, and the same counts from
TCGA-LUSC for the query split. The total frozen transfer is 720,405,670 bytes.
Every completed source is checked against the GDC MD5 and then independently
SHA-256 ledgered; the offline service receives no credentials or network
access. After completion, `build-tcga-lung-dinov2-cohort.ps1` selects a
tissue-bearing coordinate from a local overview, extracts an immutable 512 px
tile, and freezes the lung cohort. The 2.1.4-only
`stage-tcga-lung-dinov2-retrieval.ps1` then submits its two-pass qualification
campaign with an explicit cohort path and checksum.

The acquisition completed all 720,405,670 bytes and ledgered 40 checksum-bound,
patient-distinct slides. The initial interactive cohort build correctly failed
against the service-only derived-data ACL. The repaired launcher now pauses new
service claims, obtains the runner's quota snapshot, grants a temporary bounded
build ACE, restores the exact prior SDDL in `finally`, and resumes claims. The
resulting 40-sample lung cohort is immutable at SHA-256
`7bf027c6740360ec00172088d965b230442641cd041aedc1eccb7b75dc195566`.

Runner 2.1.4 subsequently installed with matching endpoint identity and active
outbound-deny rules. Campaign `dinov2-tcga-lung-retrieval-20260823-v1`
completed 80/80 GPU work units with zero retries. Signed evidence SHA-256 is
`d0d14efa935870d3089e216b476c81f82c0f019c4c10abb6f55967201ad947e0`
and signed report SHA-256 is
`a681fee3f13d14ae8a7fab46c5d1601f5e9d21b194bb26979eef26088ccab3e4`.
Recall@5 improvement passed at 0.25; NDCG@10 improvement was 0.029096055 and
missed the frozen 0.03 threshold. Repeatability, rights/integrity, offline, and
resource checks passed; OOD and full cross-tissue coverage remain unavailable.
The honest terminal verdict is `experimental`, with
`campaignCompleted=true` and `campaignTargetMet=false`.

The single pre-registered, label-blind lung tile-selection remediation then
completed as campaign `dinov2-tcga-lung-qc-remediation-20260823-v1`. It used a
frozen 40-sample cohort at SHA-256
`daaf335ba67d3cf189355b73d7d4a4081566d6518685ec4d7cb912fa013779de`,
finished 80/80 GPU work units with zero retries, and produced signed evidence
SHA-256
`ff084d41f1da82868cd550d7a8aa555032d5a5b9d040d2d8358c07d57c797b3d`
and signed attestation SHA-256
`142710f8ef70137e98cdebfa99c9f987a54e63818dc481d5632cd0837a1628e4`.
The unchanged retrieval gates passed: Recall@5 improvement was `0.15` and
NDCG@10 improvement was `0.149220098`, with exact ranking repeatability.
The track remains `experimental`, `campaignCompleted=true`, and
`campaignTargetMet=false` because frozen OOD fixtures and complete cross-tissue
patient/source-held-out coverage remain unavailable. The one remediation is
consumed; no additional lung tuning is permitted under this protocol.

The executable brightfield harness now confirms that the optical-density,
distance-transform watershed passes its bounded synthetic separated/touching
nuclei, deterministic-repeat, and reviewed-region RLE-mask checks. Generic DAB,
relative-only calibration, and weak-separation refusal checks also pass.
Cell qualification remains `experimental` pending rights-cleared cross-tissue
held-out annotations. Marker-specific IHC and PD-L1 compartment tracks remain
`not_evaluable` pending independent fixtures and reviewed compartment geometry;
synthetic behavior is not promoted into scientific qualification.

For the next IHC execution gate, the pinned 1,311,155-byte metadata bundle from
TumorQuantAI Zenodo record `21797920` passed its official MD5 and local SHA-256
checks. A four-case-disjoint subset of the smallest complete archives is frozen
at 421,890,082 bytes and is downloading through the autonomous interactive-user
acquisition task `PathLabTumorQuantIhcAcquisition`. The dataset is CC BY 4.0,
but it supplies research measurement proxies rather than independent clinical
ground truth. Completion can support descriptive ER/PR/Ki-67/HER2 integration
fixtures only; it cannot qualify clinical scoring, PD-L1, serial-section cell
matching, or either Atlas teacher lineage without a separate derivative review.
The initial full-stream transfer repeatedly lost partial progress when Zenodo
closed long responses. The repaired worker checkpoints exact 8 MiB HTTP range
chunks, retries each chunk at most three times, accepts only exact byte ranges,
and validates the assembled archive against the publisher's frozen SHA-256.
Task Scheduler no longer restarts an exhausted three-attempt transfer. A second
autonomous task, `PathLabTumorQuantIhcFixtureCampaign`, waits for acquisition,
then builds bounded case-disjoint 512 px ER/PR/Ki-67/HER2 fixtures and submits
the four-track research-only campaign. Both stages expose operational progress
on the dashboard and neither depends on Forge, the browser, or Codex remaining
open.

The bounded TumorQuantAI source acquisition subsequently completed all
421,890,082 bytes and published four checksum-verified, case-disjoint archives.
The first fixture campaign preserved a useful fail-closed engineering result:
all four requests rejected stale `.partial` paths after the atomic fixture-root
rename, so no model analysis ran. Immutable fixture set v2 resolves only bounded
relative paths after rename and validates every final file checksum while the
temporary derived-data ACE is active. Campaign
`tumorquantai-ihc-descriptive-execution-20260823-v2` then completed four offline
CPU/I/O tracks for ER, PR, Ki-67, and HER2 with signed `experimental`
attestations and no retry. Its fixture manifest SHA-256 is
`a46be25af796580897679d417ce2e3379c349d9b5a2d2b5d4b9337a01e828145`
and campaign manifest SHA-256 is
`d50676d11a55ce6b045511cf6aadc81bd783949bf8a4905e1a06879f4afdf77f`.
`campaignCompleted=true` and `campaignTargetMet=false`: executable marker-aware
descriptive behavior is proven on real source material, but the independent
held-out quantitative reference gates remain `not_evaluable`. No clinical
scoring or activation follows from this execution result.

CAMELYON lymph-node acquisition remains `NOT_EVALUABLE` and has not started.
The two official CAMELYON17 pages currently expose conflicting reuse language,
so `docs/evidence/lymph-node-source-rights-review-20260823.md` freezes the exact
blocker and required remediation. No gate is lowered and no ambiguous data may
enter evidence, Study Packs, OCI, or either Atlas lineage.

The next cell-instance gate uses the official MoNuSAC 2020 training, testing,
and supplementary artifacts (767,901,963 bytes total). Exact organizer Drive
IDs, filenames, response sizes, and CC BY-NC-SA 4.0 lineage are frozen in
`acquire-monusac2020.ps1`. The autonomous interactive-user task
`PathLabMonusacAcquisition` downloads exact 8 MiB ranges with bounded retry and
publishes local SHA-256 values. Because the organizer provides no upstream
cryptographic checksums, acquisition completion is preparation only: it remains
restricted, Atlas-Clean-ineligible, and `not_evaluable` until archive metadata,
patient/slide grouping, masks, and integrity corroboration are frozen.

MoNuSAC acquisition and cohort preparation subsequently completed. The frozen
23-patient, four-organ held-out cohort is SHA-256
`63885be5c1e271669acf9debaba2419b0b8cddeb6a7e5bf102d751e55a9727c8`;
the two published train/test patient overlaps were excluded. Campaign
`monusac-od-watershed-heldout-20260824-v1` completed with a signed
`experimental` attestation and `campaignTargetMet=false`. Macro PQ, instance
Dice, count error, and morphometry gates failed decisively while deterministic
repeat, four-tissue coverage, and failed-region rate passed.

The first evaluator also exceeded the cell pack's resource envelope and made
the operations API temporarily unresponsive. The bounded-memory replacement
reproduced the exact metrics locally in 7.32 seconds with 395.94 MiB peak heap.
Runner `2.1.6` passed the complete build, but two UAC prompts to install it were
declined, so the installed service remains `2.1.5` and the one unchanged-gate
signed remediation campaign is pending. Watershed remains a fail-closed
fallback; it is not qualified or activated. Exact results and hashes are in
`docs/evidence/monusac-cell-qualification-result-20260824.md`.

The official HoVer-Net code at revision
`67e2ce5e3f1a64a2ece77ad1c24233653a9e0901` and the 150,995,854-byte fast
MoNuSAC checkpoint are now locally acquired and checksum-frozen. Code and
weight rights remain separate: the code is MIT, while the checkpoint stays in
the MoNuSAC `CC-BY-NC-SA-4.0` restricted research lineage and is not
Atlas-Clean eligible. Safe CPU checkpoint loading succeeded in the shared
PyTorch 2.7.1+cu126 runtime, but the original post-processing dependencies are
absent. The candidate remains `not_evaluable` and inactive until a pinned
compatibility overlay or reviewed minimal adapter passes offline P2000
inference and the unchanged 23-patient held-out gates. Exact hashes and the
remaining boundary are recorded in
`docs/evidence/hovernet-candidate-acquisition-result-20260824.md`.

The checksum-pinned compatibility adapter then reached real P2000 execution as
`cell-hovernet-fast-monusac-v1/4`. It references the existing PyTorch
2.7.1+cu126 runtime instead of copying or mutating it, and adds a locally
manifested SciPy 1.18.1 overlay. Two offline synthetic probes produced the same
72 instance detections and research-category counts using 532 MiB peak VRAM
and approximately 1.1 GiB peak RAM. This is synthetic runtime conformance only,
not MoNuSAC held-out accuracy or qualification. The pack remains inactive and
`not_evaluable`; service integration, immutable progress/checkpoints, and the
unchanged 23-patient gates remain. Exact hashes and measurements are in
`docs/evidence/hovernet-runtime-probe-result-20260824.md`.

The branch runner now validates `pathlab.model-runtime-reference/1` before
launching a shared-runtime worker: exact state-root identities, canonical-path
containment, the complete shared runtime file ledger, candidate ledger, weight,
worker, and reference hashes must all pass. It also validates bounded
`pathlab.cell-instance-metrics/1` results and supplies deterministic CuBLAS
configuration. This is source-ready infrastructure only. The installed 2.1.5
service is unchanged, cell jobs are not yet routed to the external worker, and
the HoVer-Net worker still lacks durable cohort/checkpoint execution. Details
are in `docs/evidence/hovernet-runner-integration-status-20260824.md`.

The first HoVer-Net held-out campaign then completed autonomously under service
`2.1.7`. It reached an honest signed `experimental` verdict, not qualification:
all frozen accuracy, count, morphometry, and failed-region gates failed, while
determinism, four-tissue coverage, and the resource envelope passed. The
dashboard stopped at 22/23 because the worker wrote its final sidecar immediately
before exiting, and the first result retained only a failure count rather than
bounded sample-level failure codes. Runner `2.1.8` and pack version `7` repair
both observability defects without changing the model, cohort, or gates. The
real P2000 probe, full tests, repository policy verification, and exact-head
Linux/macOS/Windows CI pass. Service `2.1.8` is installed and the single
unchanged-gate remediation campaign completed at an accurately reported 23/23
units with zero retry. Its signed verdict remains `experimental` and
`campaignTargetMet=false`; the unchanged model produced the same failing
held-out metrics. The bounded worker result now retains three sample failure
records, but the signed v2 report exposes only their aggregate integrity
outcome, so exact sample failure codes remain an operator-reporting limitation.
The candidate remains inactive. Exact results and boundaries are in
`docs/evidence/hovernet-cohort-worker-status-20260824.md`.
