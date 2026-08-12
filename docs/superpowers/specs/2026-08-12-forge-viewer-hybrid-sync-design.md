# Forge–Viewer Hybrid Private Sync Design

Date: 2026-08-12

## Goal

Make Forge's Viewer library a real authenticated private library, not a list of
web links. Support bounded two-way synchronization of annotations, private
metadata, and folder placement. Remote slides open through authenticated tiles
with a bounded local cache. A user may explicitly keep a slide offline by
downloading and verifying its canonical OME-TIFF.

This design does not replace slide pixels, publish or share slides, synchronize
collections, add WebSockets, or create a general file-sync platform.

## Product behavior

The Viewer library lists actual remote folders and slides with thumbnails,
revision state, availability, and sync status. Selecting a remote slide opens
it in Forge through Viewer-owned private tiles. Tiles are cached locally but
the full image is not downloaded automatically.

**Keep offline** starts one resumable canonical-image download. Forge stages the
file, verifies its advertised length and SHA-256, and activates it through an
atomic rename. **Remove offline copy** removes only the managed local copy and
never removes the Viewer slide.

**Sync now** pulls remote changes and pushes local annotation, metadata, and
folder changes. Forge polls every five seconds only while the Viewer library is
open or a sync is active. Closing the Viewer library stops polling. Ordinary
startup makes no network request unless Forge must recover an unfinished,
user-started offline download.

## Architecture

Viewer adds a focused `desktop-sync/v1` route/service module. Forge adds one
`ViewerSyncStore` and one `ViewerSyncService`. Both reuse the existing device
pairing credential, SQLite stores, private slide model, annotation revision
model, bounded HTTP transport, and runtime admission rules.

No WebSocket, filesystem watcher, background daemon, new external runtime, or
general synchronization framework is introduced.

### Viewer endpoints

- `GET /api/v2/desktop/library/items` returns a bounded folder/slide page with
  opaque cursor, stable remote IDs, names, folder IDs, private metadata,
  readiness, image revision, content length/SHA-256, annotation revision,
  metadata revision, folder revision, thumbnail URL, and tile-source URL.
- `GET /api/v2/desktop/library/changes?after={cursor}` returns at most 500
  ordered changes and the next durable cursor. Events cover slide creation,
  metadata/folder changes, annotation revision changes, trash, and restore.
- `GET /api/v2/desktop/slides/{slideId}/content` returns only the canonical
  OME-TIFF for ready private slides. It supports `HEAD`, a single bounded byte
  range beginning at the requested offset, `ETag`, exact content length, and
  persisted SHA-256. It never accepts or returns a filesystem path.
- `PATCH /api/v2/desktop/slides/{slideId}` updates the bounded private metadata
  fields or folder ID using expected metadata and folder revisions.
- Existing desktop annotation read/batch endpoints remain authoritative and
  receive no parallel annotation schema.

Viewer library and change queries use database metadata only. They never open
or decode a WSI. Canonical-image reads stream the already persisted OME-TIFF.

### Forge components

`ViewerSyncStore` persists:

- remote slide/folder identity;
- image, annotation, metadata, and folder revisions;
- last applied change cursor;
- tile-cache and offline-copy paths;
- expected image length and SHA-256;
- download offset and phase;
- locally dirty fields; and
- conflict snapshots and resolution state.

`ViewerSyncService` owns bounded list/pull/push, tile access, resumable offline
download, cancellation, restart recovery, and conflict resolution. It never
runs conversion or analysis.

## Data flow

### Initial library sync

1. Forge requests bounded remote folders and slides.
2. Forge commits identities and revisions in one local transaction.
3. UI shows remote thumbnails and sync state.
4. No full slide downloads automatically.

### Remote viewing

1. Forge requests authenticated private DZI metadata and tiles.
2. Tile responses enter a size-bounded LRU cache.
3. Forge loads annotations through the existing desktop annotation endpoint.
4. User edits are stored locally as dirty revisions until explicit sync.

### Keep offline

1. Forge checks advertised size and local free space.
2. Forge creates or resumes a managed `.partial` file from the server-confirmed
   byte offset.
3. One bounded request streams data directly to disk.
4. Forge verifies exact length and SHA-256.
5. Atomic rename activates the managed OME-TIFF.
6. Hash/length mismatch leaves no active copy and reports a closed failure.

### Two-way sync

1. Pull ordered changes after the last committed cursor.
2. Apply changes whose corresponding local fields are clean.
3. Push dirty annotations, metadata, and folder placement using expected
   revisions.
4. Commit returned revisions and then advance the cursor.
5. Retried pages and mutations are idempotent.

## Conflict behavior

If the same object or field changed on both sides from the same base revision,
Forge records `CONFLICT`, preserves both versions, and stops synchronization for
that item. Other independent items may continue.

The user chooses:

- **Keep local**: push the preserved local value against the freshly confirmed
  remote revision;
- **Keep Viewer**: discard the local dirty value and apply the remote value; or
- **Keep both**: annotations only, creating distinct annotation objects where
  identities permit safe duplication.

There is no automatic last-write-wins policy. A remotely trashed slide with
dirty local changes becomes conflicted. It is not deleted locally. Canonical
slide pixels are immutable and never enter edit conflict resolution.

## Security and resource limits

New device scopes are `library:read`, `slides:offline:read`, and `library:sync`.
Existing `annotations:sync` remains required for annotation changes. Previously
paired credentials require explicit reconnect before receiving new scopes.

All endpoints are private, credential-bound, rate-limited, and fail closed.
Downloads expose no arbitrary path or unrestricted range access. Forge allows
one full-slide download at a time, uses at most a 1 MiB streaming buffer, and
requires free space of at least advertised size plus 10 percent. Default tile
cache cap is 2 GiB and is user-adjustable.

Sync pages contain at most 100 library items and 500 change events. On systems
with at most 16 GiB RAM, offline download does not overlap conversion or heavy
analysis. Cancellation and shutdown close streams and preserve resumable state.

## UI

Viewer library destinations become local navigation filters, not external
links. Each slide shows thumbnail, folder, remote readiness, sync state, cache
state, and offline state. Primary actions are **Open**, **Keep offline** or
**Remove offline copy**, and **Sync now**.

Conflict rows show the affected fields and open a compact resolver with **Keep
local**, **Keep Viewer**, and annotation-only **Keep both**. Progress is shown
per slide for downloads and synchronization. The UI never says synchronized
until server revisions are confirmed and committed locally.

## Testing and acceptance

Development follows red-green-refactor in each repository. Shared JSON fixtures
prove that Viewer and Forge agree on library pages, changes, revisions, ranges,
and conflicts.

Required automated coverage:

- credential scope and reconnect gates;
- bounded pagination and deterministic cursor ordering;
- duplicate/retried change pages;
- resumable ranges at initial, middle, and final offsets;
- length, ETag, and SHA-256 mismatch failures;
- cancellation and restart recovery;
- idempotent metadata/folder and annotation pushes;
- simultaneous local/remote changes and all three conflict resolutions;
- remote trash with dirty local work;
- tile-cache eviction and offline-copy removal boundaries;
- zero ordinary startup requests and stopped polling when Viewer library closes;
- responsive Viewer-library, progress, offline, and conflict UI.

End-to-end acceptance uses one real private WSI:

1. List it in Forge from Viewer.
2. Open through authenticated cached tiles.
3. Keep it offline and verify the full OME SHA-256.
4. Edit annotations, metadata, and folder placement in both directions.
5. Force and resolve a conflict without data loss.
6. Restart both products and confirm identity, cursor, offline copy, annotations,
   metadata, and folder placement survive.

## Fixed endpoint

Work stops when the endpoints, Forge store/service, hybrid cache/offline flow,
annotation/metadata/folder synchronization, conflict resolver, tests, and one
real-slide acceptance pass. Image replacement, sharing/publication,
collections, arbitrary files, live WebSockets, multi-account merging, and
cloud-drive synchronization require separate approval.
