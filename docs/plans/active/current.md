# Active Task — PIVOT Annotation-Free WSI Training MVP

Approved by the product owner on 2026-08-01. This milestone supersedes the
Forge-to-Viewer connection task while preserving its completed local connection,
preview, conversion, packaging, annotation and upload behavior.

## Goal

Add a complete, local, non-diagnostic training workflow that turns any supported
unannotated 2D RGB WSI into coordinate-grounded cross-scale exercises:

- compile deterministic tasks from the slide's own pixel pyramid and source
  coordinates;
- show a high-resolution query region and let a learner relocate it in the live
  WSI viewer;
- score answers geometrically without model predictions, annotations or disease
  labels;
- record bounded session telemetry and adapt task difficulty from learner
  performance;
- persist auditable manifests tied to the source fingerprint, series, preview
  revision, algorithm version and random seed;
- remain functional without a trained model, network service or PathLab Viewer.

## Fixed boundaries

- PIVOT is educational and non-diagnostic. It does not name morphology, rank
  clinical importance, generate diagnoses or claim diagnostic competence.
- Correctness always comes from source coordinates and transform provenance.
- A future embedding model may select hard distractors or estimate difficulty,
  but it must never define the correct answer.
- Research state stays local and separate from annotations, `.plslide`, Viewer
  upload and publication contracts.
- No public upload, automatic publication, merge, push, OCI deployment or
  production test is in scope.
- Existing viewing, conversion, packaging, annotation and Viewer connection
  behavior must not regress.
- Never load an entire WSI into memory; use bounded DZI/tile reads, caches and
  cancellable work.

## Acceptance gates

1. A supported slide with a ready preview can generate a deterministic task set
   without annotations or network access.
2. Every task records an exact source rectangle and reproducible query image.
3. A learner can start a session, view the query, navigate, submit a location,
   receive normalized geometric feedback, skip, request a bounded hint and end
   the session.
4. Session state survives refresh/restart and becomes stale if the source or
   selected series changes.
5. APIs retain loopback authentication, origin/CSRF enforcement and bounded
   request bodies.
6. The task compiler rejects blank, low-information and ambiguous candidates and
   reports why tasks were rejected.
7. Focused and complete Java/frontend checks pass, followed by real-browser
   desktop and narrow-viewport acceptance.
8. Added work is measured for cold generation, cached opening, disk size and peak
   process-tree memory; unmeasured estimates are not reported as facts.

## Ordered work

1. Define immutable PIVOT task/session contracts and deterministic fixtures.
2. Add a bounded research tile source, candidate filter and task compiler.
3. Add atomic local manifest/session persistence and source invalidation.
4. Add focused local APIs and background scheduling.
5. Build the integrated learner workspace and viewport-settled telemetry.
6. Add scoring, hints, adaptive ordering, accessibility and responsive behavior.
7. Run full regression, browser, resource and visual-fidelity acceptance.
