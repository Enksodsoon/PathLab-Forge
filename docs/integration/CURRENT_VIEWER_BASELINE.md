# Current PathLab Viewer Integration Baseline

Reviewed on 2026-07-28 against PathLab Viewer `main` after merged PR #63.

This is an internal Forge integration reference. Re-check the current Viewer tree before implementing any server-dependent task.

## Product architecture

PathLab Viewer currently runs:

```text
Caddy
FastAPI
Tusd 2.9.2
One serial background worker
SQLite WAL
Private filesystem storage
```

The API starts after migrations and storage reconciliation. The worker has heartbeat health, stale-job recovery, incomplete-upload cleanup, storage-capacity monitoring and bounded libvips settings.

## Existing slide lifecycle

```text
uploading
-> queued
-> validating
-> converting
-> ready_private
-> published
```

Other states:

```text
failed
deleting
```

The prepared-package MVP should reuse these states. `ingest_mode` distinguishes `legacy_ome` from `prepared_package`.

Prepared interpretation:

```text
queued       waiting for existing worker
validating   package/hash/archive/DZI validation
converting   atomic derivative installation and measurement
```

## Existing library domain

A slide has zero or one canonical folder and may belong to multiple collections. The library includes:

- All;
- Unfiled;
- Processing;
- Failed;
- Shared;
- Trash;
- nested folders up to depth 8;
- collections and saved views;
- bounded metadata search/facets/cursors;
- status polling for up to 100 slide IDs.

Prepared reservation should accept an optional active `folderId`; otherwise the slide enters Unfiled.

## Existing slide fields relevant to prepared import

```text
source_bytes
reserved_bytes
derivative_bytes
derivative_file_count
folder_id
thumbnail_filename
privacy_status
sha256
slide_metadata
state
error_code/error_message
```

Current metadata fields also support case ID, organ site, stain, diagnosis, course, tags, teaching note and administrator notes.

Package v1 should not automatically populate public teaching metadata. The existing Viewer metadata workflow remains authoritative and resets privacy review when public fields change.

## Storage accounting

Legacy admission currently reserves approximately original bytes + 3× original bytes + 5 GiB.

Prepared packages need a separate formula:

```text
package bytes
+ declared derivative bytes
+ configurable extraction safety headroom
```

After successful prepared import, the package is deleted and storage accounting counts only the installed derivative. During active import, `reserved_bytes` protects both package and extraction space. On failed import, the retained package remains accounted until deletion or retry.

## Current derivative contract

Final private derivative root:

```text
slide.dzi
slide_files/
thumbnail.jpg
```

Required generation settings:

```text
DZI tile size: 512
DZI overlap: 1
DZI JPEG quality: 85
Thumbnail longest edge: 640
Thumbnail JPEG quality: 82
```

The server derivative validator permits only `.dzi`, `.jpg` and `.jpeg`, rejects symlinks/special files, and requires exactly `slide.dzi`.

## Publication and sharing

Do not replace the current publication system.

The first publication grant creates validated hardlinks from the private derivative into public delivery. Later individual/share grants reuse the same canonical private derivative. Removing the final grant removes the public alias.

Publication and folder/collection sharing require explicit de-identification confirmation. Prepared import must leave `privacy_status=pending`.

## Private preview and annotations

A ready prepared slide must load through the existing route:

```text
/admin/preview/{slideId}
```

When annotations are enabled, the existing private annotation workspace should work without Forge-specific code. Forge does not upload or synchronize annotations.

## Upload transport

The current browser uploader uses:

```text
Tus chunk size: 20 MiB
Retry delays: 0, 1, 3, 5 and 10 seconds
Previous-upload discovery and resume
Upload token in metadata and Authorization header
```

Forge should remain interoperable with this server configuration.

## New server modules proposed

Keep new responsibilities focused:

```text
server/wsi_viewer/prepared_contract.py or prepared/contract.py
server/wsi_viewer/prepared_archive.py or prepared/archive.py
server/wsi_viewer/prepared_import.py or prepared/importer.py
server/wsi_viewer/desktop_auth.py
server/wsi_viewer/desktop_routes.py
```

Do not expand `main.py`, `library_routes.py` or `worker.py` with large inline implementations. Register focused routes/helpers using the current annotation/library route-registration pattern.

## Deployment strategy

Do not add a fifth importer container for the MVP. Use `Job.kind=prepared_import` in the existing serial worker so heartbeat, stale recovery, capacity monitoring and deployment topology remain unchanged.

A prepared-only worker profile may be considered only after the legacy OME path is intentionally retired.

## Public repository governance

PathLab Viewer is public. Only durable product/architecture/contracts belong there. Private Codex prompts, task orchestration, scratchpads and conversation-derived instructions remain in this private Forge repository.
