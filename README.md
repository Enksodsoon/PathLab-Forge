# PathLab Forge

**PathLab Forge** is the local desktop companion for PathLab Viewer.

It opens supported whole-slide image datasets on Windows and macOS, lets the user inspect the correct image series, crop or downsample, renders standardized 8-bit RGB, writes pyramidal OME-TIFF locally, generates PathLab-compatible DZI and thumbnail assets, processes slides in batch, and uploads prepared packages resumably.

PathLab Forge is deliberately separate from `PathLab-Viewer`.

```text
PathLab Forge desktop
    -> local WSI read / view / crop / convert
    -> local OME-TIFF retained by the user
    -> .plslide with DZI + thumbnail
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

## Run the local shell

The current app is an intentionally small, secured desktop foundation. It binds only to a
random `127.0.0.1` port, opens a one-time launch URL, and establishes an HttpOnly,
SameSite-Strict local session. State-changing requests additionally require the exact loopback
origin and a session CSRF token.

```powershell
.\gradlew.bat run --args=--serve
```

The app currently provides:

- a native operating-system file picker for OME-TIFF and VSI datasets;
- bounded OME-TIFF signature and VSI companion inspection;
- a persistent local library under the user's application-data directory;
- verified managed copies of existing OME-TIFF files, written via a partial file and SHA-256;
- local Bio-Formats 8.5 discovery through the application-data runtime folder,
  `PATHLAB_FORGE_BFTOOLS`, or `-Dpathlab.forge.bftools=...`;
- real VSI/ETS top-level series inspection with dimensions and calibration metadata;
- explicit 2D RGB series selection and an overflow-safe storage upper-bound estimate;
- one background VSI conversion at a time to tiled, LZW-compressed, pyramidal OME-BigTIFF;
- flattened-reader mapping that avoids the Bio-Formats 8.5 non-pyramidal `-noflat` writer defect;
- partial-file cleanup, TIFF signature validation, SHA-256, and atomic finalization;
- search, format filters, theme persistence, refresh, and safe library removal.

Bio-Formats is not committed or redistributed by this repository. A local runtime remains an
operator-supplied dependency until redistribution review is complete. This slice exports at 1x
only. Crop, 2x/4x/8x downsample, the full viewer/annotation workspace, DZI packaging, account
pairing, and Viewer upload remain staged and are not presented as working controls.

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
- Standardized OME-TIFF remains local in package v1.
- The server receives only manifest, sanitized DZI/JPEG tiles and `thumbnail.jpg`.
- A prepared slide enters Unfiled or an explicitly selected active folder.
- Automatic publication and privacy approval are prohibited.
- No diagnostic AI or quantitative analysis is part of this project.
- Older Windows/macOS support is designed from the beginning but claimed only after real testing.

## Planned source support

Compatibility depends on installed reader engines and real-file evidence. Intended formats include OME-TIFF, TIFF/BigTIFF, SVS, VSI with companion files, NDPI, MRXS, SCN, CZI and OIR where supported.

Never claim universal format or operating-system support without recorded evidence.
