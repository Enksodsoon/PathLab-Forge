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
- search, format filters, theme persistence, refresh, and safe library removal.

VSI conversion is intentionally blocked with a clear `reader required` state until an approved
Bio-Formats runtime and redistribution decision are available. Viewer sync, crop, annotation,
DZI packaging, and upload are not enabled in this build.

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
