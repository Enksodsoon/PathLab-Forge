# PathLab Viewer Connection Contract

## Current boundary

PathLab Forge is a separate desktop application. It prepares browser-ready slide assets locally and communicates with PathLab Viewer through HTTPS JSON APIs plus the existing tus upload transport.

PathLab Viewer already has:

- an authenticated, folder-aware slide library;
- resumable tus uploads;
- storage reservations and reconciliation;
- one serial background worker with heartbeat and stale-job recovery;
- cached private thumbnails;
- privacy-gated publication grants and hardlinked public delivery;
- administrator annotations on the existing private preview;
- bounded slide-status polling.

Forge must integrate with those contracts rather than create a parallel slide library, publication system, or worker service.

## API version

The implemented desktop ingest endpoints use `/api/v1/desktop`.

### Capabilities

```text
GET /api/v1/desktop/capabilities
Authorization: Bearer <desktop credential>
```

Expected response concepts:

- desktop API version;
- accepted `.plslide` schema versions;
- maximum package bytes;
- package extraction limits;
- supported package schemas and inventory formats;
- recommended and maximum chunk sizes (64 MiB in the current Viewer);
- thumbnail dimensions and JPEG quality;
- whether folder assignment is supported;
- current usable storage;
- tus upload endpoint.

Forge checks capabilities before packaging for a server.

### Prepared-slide reservation

```text
POST /api/v1/desktop/ingests
Authorization: Bearer <desktop credential>
```

Forge supplies:

- `displayName`;
- `.plslide` basename;
- package byte length;
- package SHA-256;
- schema version;
- declared derivative bytes;
- declared derivative file count;
- optional `folderId` when the credential permits folder placement.

PathLab Viewer returns:

- slide ID;
- current slide state;
- ingest ID and upload offset.

The server creates the slide in the current library domain. A missing folder places it in Unfiled. A supplied folder must exist and must not be in Trash.

### Upload

Upload with `PATCH /api/v1/desktop/ingests/{id}` and `Upload-Offset`. Forge uses
the capability-advertised chunk size, streams each chunk with a bounded buffer,
and falls back to 16 MiB for older Viewers.

A failed network upload must never cause local reconversion when a valid package still exists.

### Status

```text
GET /api/v1/desktop/ingests/{ingestId}
Authorization: Bearer <desktop credential>
```

The desktop ingest states are:

```text
uploading
finalizing
ready_private
failed
```

The final upload only transitions to `finalizing`. A single bounded Viewer worker
claims and validates it asynchronously; `HEAD` and status requests never perform
finalization. `ready_private` means the derivative was atomically installed and
committed to the private library.

### Private preview

When the slide reaches `ready_private`, Forge opens the existing browser route:

```text
/admin/preview/{slideId}
```

The user authenticates in the browser if needed. Forge does not embed or bypass the browser administrator session.

## Prepared package v2

New packages have this canonical order:

```text
manifest.json
manifest.sha256
inventory.ndjson
derivative/slide.dzi
derivative/slide_files/<level>/<column>_<row>.jpg
derivative/thumbnail.jpg
```

The NDJSON inventory contains one canonical path, size and SHA-256 per derivative.
`manifest.json` records its format, path, hash, file count and derivative bytes.
Existing v2 packages that use `files[]` remain accepted. For new prepared-v2
revisions the standardized OME-TIFF is temporary staging and is deleted only
after package hash, ledger, index and integrity stamp verification.

Required output contract:

```text
DZI tile size: 512
DZI overlap: 1
DZI JPEG quality: adaptively selected Q65, Q70, Q75, Q80, Q85, Q90 or Q95
DZI JPEG profile: optimized non-progressive 4:2:0 with trellis and deringing
DZI rescue profile: optimized non-progressive 4:4:4 quality ladder when 4:2:0 cannot pass
Thumbnail longest edge: 640
Thumbnail JPEG quality: 82
Thumbnail filename: thumbnail.jpg
```

Forge evaluates 64 deterministic native-resolution ROIs and chooses the smallest
quality for which minimum windowed SSIM is at least 0.970, every ROI mean
Delta E00 is at most 2.5, and edge-detail retention passes. If the 4:2:0
candidates fail, Forge evaluates the same Q65-Q95 ladder with the recorded 4:4:4
quality-rescue profile rather than weaken the quality gate or silently change the
requested crop or downsample. When both profiles pass, Forge uses the encoded
quality probe to choose the smaller compliant profile instead of stopping at the
first passing profile. The manifest records the selected quality, encoder
profile, metrics, staging/DZI/package bytes, exact predicted TAR bytes and ratios.
1.10 times staging OME is the reference target and 1.25 times is the warning
boundary. A quality-compliant package above that boundary remains reviewable so
every supported downsample preset can complete without silently reducing
resolution or weakening the quality gates; Forge shows the exact ratio before
approval.
Viewer validates one streaming TAR pass, including archive and payload hashes,
JPEG signatures, DZI geometry, declared counts and bytes. Output remains private
until the entire archive, including physical EOF, has passed.

## Library integration

A prepared slide must behave exactly like a legacy converted slide after import:

- appear in All, Unfiled, or its selected folder;
- contribute to bounded navigation counts and storage accounting;
- expose `derivativeBytes` and a cached thumbnail;
- be found by existing metadata search after metadata is edited;
- remain `privacy_status=pending` until explicit review;
- work with existing individual publication grants, folder/collection shares, Trash, restore, permanent deletion and private annotations.

Forge does not create collections or activate public shares during upload.

## Authentication roadmap

The first integration may use a revocable, scoped desktop credential created by an administrator and stored in Windows Credential Manager or macOS Keychain.

Required initial scopes:

```text
prepared:create
prepared:upload
prepared:status
folders:read      optional
```

Browser-assisted device pairing is a later hardening milestone. Desktop credentials never receive unrestricted filesystem, annotation, publication, password, or recovery access.

## Publication

Automatic public publication is disabled by default. Existing Viewer publication and sharing flows require explicit de-identification confirmation and remain the only supported public-release boundary.

## Versioning

- Never redefine schema version 2 silently.
- Breaking package changes create a new schema version.
- Viewer may accept multiple schema versions concurrently.
- Forge records the server capability response and producer versions in each job’s diagnostics.
