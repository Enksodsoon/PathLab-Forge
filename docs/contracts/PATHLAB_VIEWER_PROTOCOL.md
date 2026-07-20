# PathLab Viewer Connection Contract

PathLab Forge communicates with PathLab Viewer through HTTPS JSON APIs and tus.

## Capabilities

```text
GET /api/v1/desktop/capabilities
```

Expected concepts:

- API version;
- supported `.plslide` schema versions;
- maximum package bytes;
- archive permission;
- DZI tile size, overlap, format and policy quality.

Forge checks capabilities before building an upload for that server.

## Reservation

```text
POST /api/v1/desktop/prepared-slides
```

Forge supplies:

- display name;
- package basename;
- byte length;
- SHA-256;
- schema version;
- archive included flag.

Viewer returns:

- slide ID;
- tus upload URL;
- short-lived size-bound upload token;
- expiration.

## Upload

Use tus resumable upload. Preserve a valid local package and resume/fetch a new reservation after network or token failure. Never reconvert solely because upload failed.

## Status

```text
GET /api/v1/desktop/prepared-slides/{slideId}
```

Expected states:

```text
uploading
queued_import
importing
ready_private
failed
published
```

## Package contract

The canonical acceptance schema lives in `PathLab-Viewer/contracts/prepared-slide-v1.schema.json`.

Forge keeps a pinned copy after the server contract is implemented. A breaking change creates a new schema version; never silently redefine version 1.

## Publication

Automatic public publication is disabled by default. Forge may open private preview after `ready_private`; publication requires an explicit administrator action.
