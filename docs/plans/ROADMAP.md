# PathLab Forge Roadmap

## Milestone 1 — Dependency-light core

1. Java 17 build foundation and batch domain state machine.
2. SQLite queue persistence and restart recovery.
3. Source discovery model and deterministic naming.

No WSI reader or GUI yet.

## Milestone 2 — Batch conversion CLI

1. OME-TIFF input adapter.
2. standardized RGB OME-TIFF output.
3. DZI generation and validation.
4. `.plslide` packaging.
5. mixed valid/invalid batch behavior and report.

## Milestone 3 — Server upload

1. capability client;
2. scoped credential abstraction;
3. reservation and tus upload;
4. interruption/resume;
5. server status polling and private preview launch.

## Milestone 4 — Desktop viewer and queue UI

1. slide viewer;
2. series selection;
3. rectangle crop;
4. presets and per-slide override;
5. batch progress, pause, cancel and retry.

## Milestone 5 — VSI and format evidence

1. VSI/ETS grouping;
2. Bio-Formats adapter;
3. real VS200 evidence;
4. SVS/OME mixed batch;
5. compatibility matrix.

## Milestone 6 — Packaging

1. Windows 10/11 installer and portable build;
2. Apple Silicon build;
3. Intel macOS build;
4. legacy Windows/macOS investigation;
5. signing, licensing and update channels.

## Execution rule

Only one task belongs in `docs/plans/active/current.md`. Complete, commit, report and stop before advancing it.
