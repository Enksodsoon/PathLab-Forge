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
Existing v2 packages that use `files[]` remain accepted. The standardized
OME-TIFF remains local.

Required output contract:

```text
DZI tile size: 512
DZI overlap: 1
DZI JPEG quality: adaptively selected Q85, Q90, Q95 or Q100
Thumbnail longest edge: 640
Thumbnail JPEG quality: 82
Thumbnail filename: thumbnail.jpg
```

Forge evaluates 32 deterministic ROIs and chooses the smallest quality for which
every ROI has windowed SSIM at least 0.985 and mean Delta E00 is at most 1.5.
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
