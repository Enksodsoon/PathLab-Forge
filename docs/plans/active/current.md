# Active Task — Evidence Challenger BRACS Dataset Preparation

Approved by the product owner on 2026-08-01. This task supersedes the PIVOT
flagship milestone. PIVOT remains only a legacy spatial-navigation baseline; it
is not AI and is not the primary educational product.

## Goal

Create the reproducible first data stage for PathLab Evidence Challenger:

- accept an authorized local copy of the official BRACS WSI archive and its
  summary spreadsheet;
- preserve and independently verify the published patient-disjoint train,
  validation and test partitions;
- normalize the seven slide-level diagnostic labels without importing ROI or
  pixel annotations into the training manifest;
- validate every WSI through bounded decoded-slide inspection, tissue-content
  screening, metadata consistency and SHA-256 identity;
- emit deterministic, relative-path manifests that can feed later patch
  extraction and weakly supervised MIL training;
- never modify, copy, redistribute or commit the source WSIs.

## Fixed boundaries

- BRACS download requires truthful named registration on the official site. The
  repository does not automate registration, store credentials or bypass access.
- Raw data must remain outside the Git repository and is treated as immutable.
- The published BRACS reference splits remain the benchmark authority. The
  cleaner verifies patient separation and published slide/class counts.
- Only WSI-level labels enter the training manifest. ROI images and QuPath
  annotations are reserved for held-out evidence-localization evaluation.
- Header-only validation is allowed solely for deterministic test fixtures.
  Real training preparation requires decoded WSI and tissue QC through
  `tiffslide`.
- No model training, clinical diagnosis, Viewer upload, OCI deployment, merge or
  production release is part of this task.

## Acceptance gates

1. CSV and XLSX BRACS summaries normalize into one documented schema.
2. The cleaner fails closed on missing slides, extra slides, invalid IDs,
   label/folder disagreement, split disagreement and duplicate slide content.
3. Every accepted slide has a valid TIFF/SVS container, full SHA-256, decoded
   dimensions, pyramid-level count and a bounded thumbnail tissue measurement.
4. No patient appears in more than one official split.
5. Full-dataset mode requires exactly 547 WSIs, 189 patients and the published
   split/class distributions.
6. Outputs contain only relative paths, deterministic manifests, checksums,
   provenance and a dataset card; no raw pixels are copied.
7. Output uses partial staging and atomic finalization.
8. Focused Python tests, complete Forge checks and repository policy checks pass.
9. If authorized BRACS files are unavailable locally, the handoff is reported
   explicitly and no claim of real-slide cleaning is made.

## Ordered work

1. Confirm official access, license, folder structure and published counts.
2. Define normalized metadata and validation contracts with failing tests.
3. Implement CSV/XLSX parsing, inventory reconciliation and patient-leak checks.
4. Implement decoded-slide QC, hashing and deterministic atomic outputs.
5. Document the authorized-data handoff and exact preparation command.
6. Run focused and complete verification and record evidence.
