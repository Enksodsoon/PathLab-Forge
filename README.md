# PathLab Forge

**PathLab Forge** is the local desktop companion for PathLab Viewer.

It is responsible for opening supported whole-slide image (WSI) datasets on Windows and macOS, selecting the correct image series, viewing and cropping locally, applying controlled downsampling, rendering standardized 8-bit RGB, exporting pyramidal OME-TIFF, generating DZI/JPEG tiles, processing slides in batch, and uploading prepared slide packages resumably to PathLab Viewer.

PathLab Forge is deliberately separate from `PathLab-Viewer`.

```text
PathLab Forge desktop
    -> local WSI read / view / crop / convert
    -> .plslide package
    -> HTTPS + tus
PathLab Viewer server
    -> verify / import / preview / publish / serve
```

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
- Original proprietary WSI remains local by default.
- The server receives only a validated prepared package.
- No diagnostic AI or quantitative analysis is part of this project.
- Older Windows/macOS support is designed from the beginning but claimed only after real testing.

## Planned source support

Compatibility depends on the installed reader engines and real-file evidence. Intended formats include OME-TIFF, TIFF/BigTIFF, SVS, VSI with companion files, NDPI, MRXS, SCN, CZI and OIR where supported.

Never claim universal format support without test evidence.
