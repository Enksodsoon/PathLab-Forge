# PathLab Forge and Viewer Integration Roadmap

The desktop and server are separate repositories with one versioned package/API boundary.

- **Forge track**: local reading, conversion, batch queue, package and upload client.
- **Viewer track**: package contract, reservation, safe import, library integration and desktop authorization.

Tasks with stable boundaries may run in parallel. Integration tasks wait for both required contracts.

## Gate 0 — Current Viewer contract recorded

Complete:

- current Viewer library, storage, publication, thumbnail, worker and upload behavior reviewed;
- obsolete assumptions removed: no new publication system, no full-copy replacement, no separate importer container for MVP;
- prepared package v2 aligned to the current derivative layout.

## Forge Milestone F1 — Dependency-light core

1. Java 17 build foundation and batch domain state machine.
2. SQLite queue persistence and restart recovery.
3. Source discovery model, dataset grouping and deterministic naming.

No WSI reader or GUI yet.

## Viewer Milestone V1 — Public package contract

1. Add durable `PREPARED_SLIDE_INGEST.md` architecture.
2. Add canonical `.plslide` v1 JSON Schema.
3. Add typed manifest validation and deterministic synthetic fixtures.
4. Validate current DZI and thumbnail contract:
   - `slide.dzi`;
   - 512-pixel tiles;
   - overlap 1;
   - adaptive JPEG quality 85, 90, or 95 under strict fidelity gates;
   - `thumbnail.jpg`, longest edge 640, quality 82.

This milestone has no API, model, state or worker behavior change.

## Forge Milestone F2 — Local derivative pipeline

1. OME-TIFF input adapter and metadata inspection.
2. controlled crop/downsample RGB OME-TIFF output.
3. DZI and thumbnail generation matching Viewer V1.
4. local derivative validation and OpenSeadragon preview.
5. canonical `.plslide` v2 packaging with NDJSON inventory and legacy-v2 compatibility.
6. mixed valid/invalid batch behavior and report.

## Viewer Milestone V2 — Prepared reservation and storage accounting

1. Add `ingest_mode` with backward-compatible default `legacy_ome`.
2. Keep existing slide states; do not add import-only states in the MVP.
3. Add prepared reservation fields in an ingest metadata structure.
4. Add a prepared admission formula based on package bytes + declared derivative bytes + configurable extraction safety headroom.
5. Accept an optional active `folderId`; otherwise place the slide in Unfiled.
6. Store package upload size in existing `source_bytes` for display, but exclude it from post-import storage accounting because the package is deleted after successful installation.
7. Preserve current legacy OME reservation and retry behavior.

## Viewer Milestone V3 — Safe import in the existing worker

1. Extend upload finalization by `ingest_mode` rather than TIFF signature alone.
2. Store prepared packages in a private package path.
3. Queue `Job.kind=prepared_import`.
4. Reuse current worker scheduler, heartbeat, stale recovery and one-job-at-a-time execution.
5. Map prepared processing onto current states:
   - queued;
   - validating;
   - converting/installation;
   - ready_private.
6. Validate hash, TAR paths/types/counts/bytes, manifest, DZI XML, JPEG signatures and exact package layout.
7. Atomically install the derivative into the existing private derivative root.
8. Measure derivative bytes/file count using current storage validation.
9. Set `thumbnail_filename=thumbnail.jpg`, image metadata, privacy pending, reservation zero and ready status.
10. Delete the uploaded package only after committed success.
11. Preserve a failed package for bounded retry or explicit re-upload.

No QuPath, Bio-Formats or image conversion is added to Viewer.

## Viewer Milestone V4 — Scoped desktop API

1. Add `/api/v1/desktop/capabilities`.
2. Add `/api/v1/desktop/ingests` reservation.
3. Add `/api/v1/desktop/ingests/{ingestId}` upload and status.
4. Advertise 64 MiB chunks while retaining a 16 MiB legacy fallback.
5. Add a revocable, hashed, scoped desktop credential.
6. Initial scopes: prepared create/upload/status and optional folder read.
7. Keep browser CSRF/session authentication unchanged.
8. Defer browser-assisted device pairing until end-to-end upload works.

## Forge Milestone F3 — Automatic upload

1. Viewer capability client.
2. Credential Manager/Keychain abstraction.
3. optional folder-target discovery.
4. prepared-slide reservation.
5. streamed capability-sized upload with bounded retry.
6. resume without reconversion.
7. poll current Viewer states and expose `SERVER_PROCESSING` locally.
8. open `/admin/preview/{slideId}` after ready.
9. delete local package only after confirmed import when the cleanup policy requests it.

## Forge Milestone F4 — Desktop viewer and queue UI

1. slide viewer and series selection;
2. rectangle crop;
3. shared presets and per-slide override;
4. batch table and storage preflight;
5. pause, cancel, retry and restart recovery;
6. server/folder destination panel;
7. private-preview launch and final batch report.

## Forge Milestone F5 — VSI and format evidence

1. VSI/ETS grouping;
2. Bio-Formats adapter;
3. real VS200 evidence;
4. SVS/OME/VSI mixed batch;
5. compatibility matrix and stable errors.

## Forge Milestone F6 — Packaging

1. Windows 10/11 installer and portable build;
2. Apple Silicon build;
3. Intel macOS build;
4. Windows 8.1/7 and macOS 10.13–10.15 investigation;
5. signing, licensing and update channels.

## Integration gate

Production integration is not complete until:

- a Forge-generated package imports through tus;
- the slide appears in All/Unfiled or the selected folder;
- navigation/storage/status update without refresh errors;
- thumbnail and private preview load;
- annotations work in private preview when enabled;
- existing publication grants, folder/collection sharing, Trash, restore and deletion work unchanged;
- backup/reconcile rebuild public deliveries;
- legacy OME upload remains functional;
- interrupted upload and server restart recovery are evidenced.

## Execution rule

Each repository exposes only one active task. Complete, commit, report and stop before advancing it.
