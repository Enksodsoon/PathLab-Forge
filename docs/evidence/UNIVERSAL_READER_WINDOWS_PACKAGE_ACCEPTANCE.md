# Universal Reader Windows Package Acceptance

Date: 2026-08-14

## Decision

The Windows x86-64 reader package is suitable for internal compatibility testing. It is explicitly `NON_REDISTRIBUTABLE`; it is not a signed release and must not be published while the redistribution review remains pending.

## Package and runtime evidence

- Application image: 3,799 files and 503,145,315 bytes.
- Reader manifest fingerprint: `4908c69a6abc52ae2d7b4072a9a8fe0d5b291173127c40844e2a019eccfc97ee`.
- Runtime catalog: 179 Bio-Formats scientific formats and 21 libvips loaders (200 advertised capabilities in this build).
- Clean application-data launch found the package-relative runtime without developer environment overrides.
- Bounded self-test decoded real center regions with Bio-Formats, libvips, the MDS reader, the SDPC native reader and libisyntax.
- The fixture matrix covered SVS, MDS, SDPC, iSyntax, JPEG-2000, OME-TIFF and PNG.

The package contains owner-supplied runtime files copied from external staging. No runtime binary, installer or source slide is committed to the repository.

## Local and Viewer workflow

- A real SVS produced a 2,220 x 2,967 DZI and a readable JPEG tile.
- The selected-view conversion produced a 1,172,300-byte pyramidal RGB OME-TIFF with SHA-256 `51a2cb211550dff79e63d3800ca5b17baae74d2d66fdd77f9d4be9f28dc2facd`.
- Bio-Formats reopened the injected OME metadata at the correct dimensions and three channels.
- Repeating the conversion reused the verified artifact revision rather than rebuilding it.
- The authorized private Viewer accepted the OME pyramid, created private slide `f61b0fb5-f928-45c6-9f72-f135f5236832`, and completed the structured-results delivery.
- Restarting the packaged application with the same application-data directory recovered the dataset, saved view and approved artifact revision.

## Broad SVS evidence

The current packaged-runtime rescan of the user profile and WSI pool found 29 SVS paths: 25 structurally valid files opened and decoded a center tile, four had invalid or truncated TIFF structures, and zero structurally valid files had a reader failure. An earlier broader host snapshot found 162 paths (136 opened, 26 corrupt, zero reader failures); many duplicate or temporary paths from that snapshot are no longer present. Corrupt files remain reported as corrupt rather than being counted as reader defects.

## Gates that remain closed

- `productionDist` remains fail-closed until the authorized licensing review changes all required redistribution statuses to `APPROVED`.
- The internal application image is unsigned and has not been tested as a signed installer.
- Windows 10 and macOS x86-64/arm64 package matrices have not been run.
- SDPC remains owner-supplied and Windows-only pending separate decoder and FFmpeg redistribution approval.
- No push, merge, signing, publication or deployment is part of this evidence.

Compatibility claims remain fixture- and capability-scoped. Recognized, pixel-decoded, exported, Viewer-rendered and uploaded are separate levels.
