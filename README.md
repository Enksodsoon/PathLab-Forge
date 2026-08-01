# PathLab Forge

**PathLab Forge** is the local desktop companion for PathLab Viewer.

It opens supported whole-slide image datasets on Windows and macOS, lets the user inspect the correct image series, crop or downsample, renders standardized 8-bit RGB through a temporary pyramidal OME-TIFF, generates a compact PathLab-compatible DZI, processes slides in batch, and uploads prepared packages resumably.

PathLab Forge is deliberately separate from `PathLab-Viewer`.

```text
PathLab Forge desktop
    -> local WSI read / view / crop / convert
    -> temporary local OME-TIFF staging
    -> verified .plslide with compact DZI + thumbnail
    -> staging OME and loose DZI removed
    -> HTTPS + tus
PathLab Viewer server
    -> safe validation / import
    -> existing library folder or Unfiled
    -> thumbnail / private preview / annotations
    -> existing privacy review / publication / sharing
```

## Current Viewer integration

The latest Viewer is a folder-aware library rather than a flat upload list. Forge must integrate with its current:

- folders, collections, saved views and Trash;
- storage reservations and derivative accounting;
- serial worker, heartbeat and stale-job recovery;
- cached thumbnails;
- privacy-gated publication grants and folder/collection sharing;
- private administrator annotation workspace.

Forge does not replace any of those systems.

## Run the viewer-first shell

The desktop shell binds only to a
random `127.0.0.1` port, opens a one-time launch URL, and establishes an HttpOnly,
SameSite-Strict local session. State-changing requests additionally require the exact loopback
origin and a session CSRF token.

```powershell
.\gradlew.bat run --args=--serve
```

The React/Vite interface consumes Viewer-owned `@pathlab/viewer-ui`
`0.1.0-rc.373ca749.2`, pinned to Viewer design baseline
`373ca7497ffe24d2c8e064f080861554df0b5cb9`. Its lockfile records the immutable
release integrity. The shell opens with the slide navigator, centered
OpenSeadragon canvas, Viewer menu order, responsive rail, theme semantics, and
right-side crop/annotation/history inspector.

The app provides:

- a native operating-system file picker for OME-TIFF and VSI datasets;
- bounded OME-TIFF signature and VSI companion inspection;
- a persistent local library under the user's application-data directory;
- bounded source and converted-result DZI previews for OME-TIFF and VSI/ETS;
- one atomic VSI dataset inventory with relative paths, sizes, modification identities,
  SHA-256 fingerprints, and strict companion revalidation;
- local Bio-Formats 8.5 discovery through the application-data runtime folder,
  `PATHLAB_FORGE_BFTOOLS`, or `-Dpathlab.forge.bftools=...`;
- real VSI/ETS top-level series inspection with dimensions and calibration metadata;
- explicit 2D RGB series selection and an overflow-safe storage upper-bound estimate;
- one background conversion at a time for OME-TIFF and VSI through a temporary,
  tiled pyramidal OME-BigTIFF staging image;
- flattened-reader mapping that avoids the Bio-Formats 8.5 non-pyramidal `-noflat` writer defect;
- source-coordinate crop and annotations with 1x, 1.5x, 2x, 4x, and 8x presets;
- annotation-free PIVOT training generated from reusable local DZI tiles, with
  coordinate-grounded scoring, adaptive difficulty, and local research telemetry;
- immutable artifact revisions, approval invalidation, cancellation checkpoints,
  disk preflight, DZI/tile validation, and deterministic package manifests;
- source/output quality review before exact-revision approval;
- short-lived browser pairing to a local Viewer, Windows Credential Manager storage,
  resumable prepared-ingest upload, private preview, and revision-aware annotation sync;
- partial-file cleanup, TIFF/DZI validation, SHA-256, and atomic finalization;
- search, format filters, theme persistence, workspace restore, and safe library removal.

Bio-Formats is not committed or redistributed by this repository. A local runtime remains an
operator-supplied dependency until redistribution review is complete. The same is true of the
local libvips runtime used for streaming derivatives.

## Hardware profile

The minimum supported conversion profile is 6 logical CPU cores and 8 GB RAM.
Forge detects the host at startup and scales the Bio-Formats region pool, libvips
concurrency, cache, and process-tree limits on higher-specification machines. The
minimum is a compatibility floor, not a fixed resource cap: a machine with more
cores and memory receives a larger bounded profile while retaining pause and
low-memory safety thresholds.

## Start with Codex

1. Read `CODEX_START_HERE.md`.
2. Read `AGENTS.md`.
3. Work on one task from `docs/plans/active/current.md`.
4. Commit, report evidence, and stop.

## Product principles

- Batch conversion is core, not optional.
- One active WSI conversion by default.
- One active upload by default.
- The next slide may convert while the prior slide uploads when resources permit.
- Original proprietary WSI remains local.
- Prepared-v2 retains only the verified compact DZI package; legacy OME revisions remain readable.
- The server receives only manifest, sanitized DZI/JPEG tiles and `thumbnail.jpg`.
- A prepared slide enters Unfiled or an explicitly selected active folder.
- Automatic publication and privacy approval are prohibited.
- No diagnostic AI or clinical quantitative analysis is part of this project. PIVOT is a
  non-diagnostic education workflow whose answers come only from image coordinates.
- Older Windows/macOS support is designed from the beginning but claimed only after real testing.

## Planned source support

Compatibility depends on installed reader engines and real-file evidence. Intended formats include OME-TIFF, TIFF/BigTIFF, SVS, VSI with companion files, NDPI, MRXS, SCN, CZI and OIR where supported.

Never claim universal format or operating-system support without recorded evidence.
