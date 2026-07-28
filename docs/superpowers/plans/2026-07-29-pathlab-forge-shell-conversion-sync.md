# PathLab Forge Viewer-Parity Desktop Shell Implementation Plan

> **For agentic workers:** Execute task-by-task with strict red-green-refactor cycles and review gates.

**Goal:** Build a staged PathLab Forge desktop shell with Viewer-parity UI, a persistent local library, OME-TIFF/VSI conversion, prepared upload, and bidirectional annotation sync.

**Architecture:** PathLab Viewer owns the shared React UI package and prepared-upload/server contracts. PathLab Forge runs a Java 17 loopback service, serves the shared React shell, persists local state in SQLite, and isolates the commercially licensed Bio-Formats adapter.

**Tech stack:** Java 17, Gradle 9.6.1, JUnit 5.14.4, SQLite, React/Vite, pnpm 11.9, OpenSeadragon, Bio-Formats 8.5.0.

## Global constraints

- Viewer parity is pinned to commit `de03a617cd9db467c3146795a0afec95f4f55e32`.
- Forge starts from `codex/rebase-plan-to-viewer-v2`.
- Work in isolated `codex/` branches; do not work on or merge `main`.
- No production deployment, package publication, installer, signing, or automatic publication.
- Production image processing is tilewise and bounded; never load a complete WSI.
- Original datasets stay in place. Generated files and SQLite live under a user-selected managed library root.
- Only OME-TIFF and complete VSI/ETS datasets are v1 input claims.
- Bio-Formats binaries remain private until commercial license evidence and redistribution terms are recorded.
- Viewer remains authoritative for public delivery, privacy review, publication, sharing, server Trash, and browser authentication.

## Delivery sequence

### 1. Viewer shared UI package

- Branch `codex/shared-pathlab-ui-package`.
- Extract Viewer theme, brand, library shell, dialogs, OpenSeadragon shell, and annotation workspace into `packages/pathlab-ui`.
- Export host-injected API interfaces; shared code must not import Viewer API modules.
- Refactor Viewer to consume the workspace package with no behavior or screenshot drift.
- Configure proprietary `@enksodsoon/pathlab-ui` GitHub Package metadata, but do not publish.

### 2. Viewer prepared ingest

- Branch `codex/prepared-slide-ingest`, stacked on the reviewed UI branch.
- Add canonical `.plslide` v1 schema and deterministic TAR validation.
- Add prepared storage accounting and `prepared_import` handling to the existing serial worker.
- Reuse `uploading -> queued -> validating -> converting -> ready_private` through `ingest_mode`.
- Preserve all legacy OME upload behavior.

### 3. Viewer desktop and annotation sync APIs

- Branch `codex/desktop-annotation-sync`, stacked on prepared ingest.
- Add browser-admin desktop credential list/create/revoke endpoints.
- Add capabilities, folder reads, prepared reservation/status, and scoped annotation routes.
- Scopes: `prepared:create`, `prepared:upload`, `prepared:status`, `folders:read`, `annotations:read`, `annotations:write`.
- Restrict access to slides linked to the credential's stable desktop client ID.

### 4. Forge local shell

- Complete Java 17 build and immutable F1.1 domain state machine first.
- Add `forge-core`, `forge-store`, `forge-conversion`, and `forge-app` Gradle modules plus `apps/web`.
- Run a random-port `127.0.0.1` service with one-time launch token, SameSite HttpOnly session, Origin checks, and CSRF on writes.
- Use native Java dialogs for dataset selection.
- Persist local folders, collections, saved views, metadata/search, Trash, batches, annotations, revisions, drafts, and sync state in SQLite WAL.
- Consume the exact reviewed shared UI package and preserve Viewer light/dark/system, responsive, keyboard, and accessibility behavior.

### 5. Forge conversion

- Group `.vsi` with `.ets` companions and enable `cellsens.fail_on_missing_ets`.
- Inspect series tilewise; show thumbnails, dimensions, calibration, and layout.
- Support selected series, optional source-pixel rectangle crop, and 1x/2x/4x/8x downsample.
- Write pathology-standard 8-bit RGB/sRGB pyramidal OME-TIFF through `.partial` and atomic rename.
- Generate DZI tile size 512, overlap 1, JPEG quality 85 and `thumbnail.jpg` longest edge 640, JPEG quality 82 from the completed OME-TIFF.
- Validate dimensions, calibration, pyramid, DZI XML, tile bounds, JPEG signatures, counts, sizes, and hashes.
- Preserve complete checkpoints across upload failure; cancellation removes only incomplete `.partial` artifacts.

### 6. Forge upload and bidirectional annotation sync

- Build deterministic `.plslide` TAR containing only manifest, DZI, JPEG tiles, and thumbnail.
- Negotiate capabilities, reserve an Unfiled or selected active folder slide, and upload with persisted 20 MiB tus chunks.
- Resume by URL and offset; replace expired tokens without reconversion.
- Poll Viewer states through local `SERVER_PROCESSING`, then open the ready-private route.
- Store Viewer secrets only in Windows Credential Manager or macOS Keychain.
- Keep a last-common annotation snapshot and remote version.
- Auto-merge disjoint changes; require manual three-way resolution for same-field, geometry, and delete-versus-edit conflicts.
- Restart reconciliation after a new `409 ANNOTATION_CONFLICT` without discarding drafts or choices.

## External interfaces

Viewer route families:

```text
GET    /api/v2/desktop/capabilities
GET    /api/v2/desktop/folders
POST   /api/v2/desktop/prepared-slides
GET    /api/v2/desktop/prepared-slides/{slideId}
GET    /api/v2/desktop/prepared-slides/{slideId}/annotations/manifest
GET    /api/v2/desktop/prepared-slides/{slideId}/annotations/layers
GET    /api/v2/desktop/prepared-slides/{slideId}/annotations/items
POST   /api/v2/desktop/prepared-slides/{slideId}/annotations/batch
```

Existing tus transport remains `/api/v1/uploads/`.

Package v1:

```text
manifest.json
derivative/slide.dzi
derivative/slide_files/<level>/<column>_<row>.jpg
derivative/thumbnail.jpg
```

Annotations use `pathlab-annotations/v1` and sync after `ready_private`; they are not embedded in `.plslide`.

## Verification

- Preserve all existing Viewer backend, web, annotation, browser, migration, bundle-budget, public-isolation, and container checks.
- Add shared-package contract and light/dark desktop/mobile visual parity checks.
- Test archive traversal, absolute paths, links, duplicates, count/byte limits, malformed DZI/JPEG, hash mismatch, rollback, storage reconciliation, and legacy ingest.
- Test every Forge state transition, immutable update, migration cycle, restart recovery, library workflow, annotation revision, source fingerprint, and merge conflict.
- Test missing ETS, ambiguous series, crop/downsample, color/scale behavior, bounded reads, cancellation, OME/DZI validation, deterministic output, tus resume, and retry without reconversion.
- Public CI uses synthetic fixtures. Licensed VSI tests run only in protected CI or locally.
- Real acceptance requires a private complete VSI/ETS dataset and proves inspect, crop/downsample, restart recovery, validated output, annotation reload, interrupted upload resume, ready-private import, bidirectional sync, and manual conflict resolution.
- Report local checks and remote CI separately. Stop before merge, publication, or deployment.
