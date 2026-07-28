# PathLab Forge Agent Guide

## Purpose

PathLab Forge is a standalone Windows/macOS desktop application. It performs computationally expensive WSI work locally and connects to PathLab Viewer through a versioned API, resumable tus upload and the `.plslide` package.

The separate PathLab Viewer repository owns the server library, storage accounting, package import, private preview, publication grants, folder/collection sharing, Trash, annotations and static delivery.

## Read order

For every Codex run:

1. `docs/plans/active/current.md`
2. files explicitly listed by that task
3. `docs/architecture/SYSTEM.md` only when architecture context is needed
4. `docs/contracts/PATHLAB_VIEWER_PROTOCOL.md` when server communication is involved
5. `docs/integration/CURRENT_VIEWER_BASELINE.md` when a task depends on Viewer internals
6. `docs/compatibility/OS_MATRIX.md` when platform behavior is involved

Do not read or implement every future milestone.

## Current Viewer integration rules

- New desktop APIs belong under `/api/v2/desktop`; tus remains `/api/v1/uploads/`.
- Forge local states do not mirror Viewer states one-to-one.
- Viewer’s MVP prepared flow reuses `uploading → queued → validating → converting → ready_private` and distinguishes the path through `ingest_mode`.
- Forge uses local `SERVER_PROCESSING` while polling the exact server state separately.
- Package v1 contains only DZI/JPEG derivative files and the manifest; the standardized OME-TIFF stays local.
- Package output must match Viewer’s current DZI and thumbnail contract.
- Forge may choose Unfiled or an existing active folder; it does not create public grants or mark privacy review as passed.
- Existing Viewer publication, sharing, annotations, Trash and restore behavior must remain server-owned.

## Product boundaries

PathLab Forge owns:

- WSI dataset discovery and reader routing;
- VSI companion-file grouping;
- local viewing and image-series selection;
- crop and downsample;
- standardized RGB rendering;
- pyramidal OME-TIFF export;
- DZI/JPEG and cached thumbnail generation;
- deterministic `.plslide` packaging;
- persistent batch queue;
- resumable upload;
- Windows/macOS packaging.

PathLab Forge does not own:

- the server database or deployment;
- public tile delivery;
- Viewer library search, collections, sharing or Trash;
- Viewer annotations;
- student/teacher workflows;
- AI diagnosis;
- fluorescence analysis;
- Z-stack or time-series navigation;
- PACS/DICOM integration;
- automatic public publication by default.

## Working rules

- Work on exactly one active-plan task.
- Create a focused `codex/` branch.
- Use test-driven development for behavior changes.
- Prefer small interfaces and adapters over direct dependency coupling.
- Keep shared core compatible with Java 17 unless a lower target is proven necessary.
- Hide QuPath, Bio-Formats, OpenSlide and libvips details behind focused adapters.
- Never load an entire WSI into memory.
- Use bounded caches, tiled/streaming I/O and cancellable operations.
- Use `.partial` staging and atomic rename for outputs.
- Preserve completed local packages across upload failures.
- Never reconvert solely because upload failed.
- Do not invent a Viewer API, schema, thumbnail policy or state transition that conflicts with the reviewed baseline.
- Commit the completed task, return the required report, and stop.

## Resource defaults

- Active conversions: 1
- Active uploads: 1
- Low-resource mode: conversion and upload sequentially
- Never enable unlimited reader, conversion or upload concurrency

## Prohibited commits

Never commit:

- patient/source WSI or companion files;
- generated OME-TIFF;
- generated DZI tile trees;
- normal `.plslide` packages;
- tokens, passwords, server credentials or keychain exports;
- local queue databases;
- runtime bundles, installers or build output;
- proprietary vendor libraries without documented redistribution rights.

Tiny deterministic synthetic fixtures generated during tests are permitted.

## Verification

The active task defines focused commands. Once a build system exists, each behavior task must run:

- focused tests;
- complete project tests;
- formatting/static checks;
- repository policy checks from `scripts/verify-repo.*`.

Platform packaging tasks must test the actual artifact on the named operating system before claiming support.

Viewer integration tasks must also verify their expected contract against the current PathLab Viewer API/schema fixture rather than relying on copied prose alone.

## Completion report

```text
TASK RESULT
- Task:
- Branch:
- Commit:
- Files changed:
- Focused tests:
- Full checks:
- Evidence:
- Known limitations:
- Next task:
```

Stop after the report.
