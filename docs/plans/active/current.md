# Active Task — PathLab ADAPT Approval-First Study Delivery

Approved by product owner on 2026-08-02. This task supersedes the completed
Unannotated-WSI Evidence MIL v2 research milestone. BRACS lesion/MIL artifacts
remain outside ADAPT and are not approved for product integration.

## Goal

Build a faculty-controlled, content-agnostic educational research workflow
across PathLab Forge and Viewer. ADAPT uses keyed or coordinate-retrieval tasks
and pseudonymous learner telemetry. It never analyzes slide pixels, generates
medical answer keys, makes clinical claims, or runs learner experiments before
institutional approval.

## Task 1 — Contracts and approval boundary

- Version Study Pack, task, event, prediction, model-manifest, coach, protocol,
  and analysis-snapshot contracts.
- Add faculty-authorized research resources and token-isolated learner routes.
- Keep research disabled by default and forbid collection before consent.

## Task 2 — Forge Study Pack authoring

- Link existing Viewer slides without copying WSI data.
- Generate coordinate tasks from approved PIVOT manifests.
- Import QTI, Moodle XML, CSV, and Anki material with answer/source/author/
  license/revision provenance.
- Refuse keyed tasks missing an imported or faculty-approved key.
- Store immutable pack versions and publish privately through pairing.

## Task 3 — Viewer Study Mode

- Support pseudonymous invitation redemption, consent, adaptive cards,
  measurement lockout, confidence/source checks, offline event batching,
  restoration, withdrawal, and responsive mouse/touch/stylus use.
- Keep keys and target coordinates server-side.
- Use only deterministic, approved actions; fall back to fixed order on OOD,
  uncertainty, runtime weakness, or model failure.

## Task 4 — TRACE-Former research pipeline

- Build deterministic OULAD, capped EdNet, and synthetic adapters with a
  license ledger and learner-disjoint/time-forward splits.
- Implement teacher, baseline, distillation, calibration, OOD, browser export,
  Pareto selection, and resource gates.
- Do not release an artifact or positive claim unless every prespecified gate
  is measured and passes. Synthetic results validate software only.

## Task 5 — Research and manuscript workflow

- Freeze novelty queries and claim/source evidence.
- Freeze protocols and analysis snapshots before normal-use evaluation.
- Generate deterministic, traceable IMRaD drafts whose language is constrained
  by study design and evidence state.
- Missing or unmatched follow-up data must produce `inconclusive`.

## Task 6 — Verification and delivery

- Test schema compatibility, event idempotency, offline queues, restoration,
  withdrawal, redaction, token isolation, answer leakage, accessibility,
  browser/device behavior, and bounded resource use.
- Keep implementation, local commits, PR, merge, deployment, and production
  activation as separate gates. This task authorizes implementation and local
  verification only.

## Fixed boundaries

- Education/research only; no clinical diagnosis or treatment use.
- No slide-pixel analysis or medical-answer generation.
- No randomization, live experimentation, or causal claims.
- No anonymous research-data access.
- Released model weights are frozen; no online weight training.
- Confidence/source heads remain shadow-only until approved real-data evidence.
- `To our knowledge` is the strongest novelty wording until formal prior-art
  review supports anything stronger.
- No cloud spending, PR, merge, release, deployment, or activation.

## Local implementation checkpoint — 2026-08-02

- Tasks 1–6 are implemented and locally verified on the isolated Forge and
  Viewer ADAPT branches (`b77d499` and `c0ce4a8`, respectively).
- Research mode remains disabled by default. TRACE delivery is fixed-order only;
  every locally produced model manifest is `not_approved`.
- Study Packs, invitations, consent, sessions, telemetry, withdrawal, protocol
  freeze, coach fallback, evidence registries, analysis snapshots, and
  deterministic manuscript artifacts have versioned, tested local workflows.
- The formal novelty search, investigator signoff, institutional approval,
  approved Safe-AI curriculum content, real OULAD/EdNet benchmark, approved
  human follow-up analysis, and external coach-provider verification have not
  been performed.
- ONNX/ONNX Runtime and browser WebGPU/WASM execution, the actual 8-GB reference
  device, Edge, tablet hardware, and physical assistive-technology checks remain
  unverified and therefore cannot authorize adaptive delivery or efficacy,
  novelty, capacity, deployment, or production-readiness claims.
- Final local verification is `PASS_WITH_UNVERIFIED`: Forge passed 180 Java
  tests (2 skipped), 43 frontend tests, and 138 AI-training tests; Viewer passed
  475 backend tests (5 skipped), 251 frontend tests, and 12 research browser
  tests. The bounded 150-session local contract completed with zero request
  errors. This is not production-capacity or educational-efficacy evidence.
- Detailed verification is recorded under `.superpowers/sdd/current`; PR review,
  merge, deployment, and production activation remain separate future gates.
