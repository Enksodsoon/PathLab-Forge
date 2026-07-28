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

New desktop endpoints use `/api/v2/desktop`. The existing tus transport remains `/api/v1/uploads/`.

### Capabilities

```text
GET /api/v2/desktop/capabilities
Authorization: Bearer <desktop credential>
```

Expected response concepts:

- desktop API version;
- accepted `.plslide` schema versions;
- maximum package bytes;
- package extraction limits;
- DZI tile size, overlap, format and JPEG quality;
- thumbnail dimensions and JPEG quality;
- whether folder assignment is supported;
- current usable storage;
- tus upload endpoint.

Forge checks capabilities before packaging for a server.

### Prepared-slide reservation

```text
POST /api/v2/desktop/prepared-slides
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
- tus upload URL;
- short-lived size-bound upload token;
- token expiration.

The server creates the slide in the current library domain. A missing folder places it in Unfiled. A supplied folder must exist and must not be in Trash.

### Upload

Use the existing tus endpoint and upload-token metadata/header contract. Forge must persist its tus fingerprint, resume interrupted uploads, and request a replacement reservation or token when required.

A failed network upload must never cause local reconversion when a valid package still exists.

### Status

```text
GET /api/v2/desktop/prepared-slides/{slideId}
Authorization: Bearer <desktop credential>
```

The MVP deliberately reuses PathLab Viewer’s current slide states:

```text
uploading
queued
validating
converting
ready_private
failed
published
deleting
```

For prepared packages their meanings are:

- `queued`: waiting for the existing serial worker;
- `validating`: checksum, archive, manifest, DZI and file validation;
- `converting`: atomic derivative installation and measurement; no WSI conversion occurs;
- `ready_private`: available in the library, thumbnail endpoint, private preview, annotation workspace and later publication/sharing workflows.

The desktop status payload may expose a more specific `phase`, but Forge must not require new database slide states for the MVP.

### Private preview

When the slide reaches `ready_private`, Forge opens the existing browser route:

```text
/admin/preview/{slideId}
```

The user authenticates in the browser if needed. Forge does not embed or bypass the browser administrator session.

## Prepared package v1

The canonical acceptance schema will live in:

```text
PathLab-Viewer/contracts/prepared-slide-v1.schema.json
```

Forge keeps a pinned compatible copy after the Viewer contract is merged.

Version 1 contains only server-ready derivative assets:

```text
manifest.json
derivative/slide.dzi
derivative/slide_files/<level>/<column>_<row>.jpg
derivative/thumbnail.jpg
```

Version 1 does not upload the standardized OME-TIFF. Forge keeps that file locally. This minimizes server storage and keeps server import free of WSI decoding.

Required output contract:

```text
DZI tile size: 512
DZI overlap: 1
DZI JPEG quality: 85
Thumbnail longest edge: 640
Thumbnail JPEG quality: 82
Thumbnail filename: thumbnail.jpg
```

The server measures the installed derivative and verifies it against the manifest and reservation. Manifest declarations are never trusted by themselves.

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

- Never redefine schema version 1 silently.
- Breaking package changes create a new schema version.
- Viewer may accept multiple schema versions concurrently.
- Forge records the server capability response and producer versions in each job’s diagnostics.
