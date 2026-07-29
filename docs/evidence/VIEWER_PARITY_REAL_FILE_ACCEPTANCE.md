# Viewer parity and real-file acceptance

Date: 2026-07-29 (Asia/Bangkok)

This evidence was produced with private local samples. The source files and
generated artifacts were not committed, attached to either pull request, or
uploaded outside the isolated local Viewer acceptance instance.

## Shared interface contract

- Viewer design baseline: `373ca7497ffe24d2c8e064f080861554df0b5cb9`
- Viewer-owned package: `@pathlab/viewer-ui@0.1.0-rc.373ca749.2`
- Release asset SHA-256:
  `635b8760dd73e98fd9c106378d882e16df197fce1eb4fbd40de6acc7b3e917b5`
- Forge lockfile integrity:
  `sha512-uC0fYxdtYeWLc/LfdPgisDN5Mj7TCO6n3tOn1Yx3GzyQ8pt783XDgrBmjwusq+tbZGNlVTRThPbIyvI8IdbE5w==`
- Prepared-ingest protocol: `pathlab-prepared-slide/v2`

## Source acceptance

OME-TIFF:

`C:\Users\enkso\Downloads\Test\Slide no.7-CS22-123.ome.tif`

- fingerprint prefix: `8245aa7d5afc6fd5`
- source size: 331.7 MB
- series: 37,800 x 34,366
- source preview reopened through bounded local DZI
- a point annotation persisted in source coordinates at `18900,17183`

VSI/ETS:

`C:\Users\enkso\Downloads\New folder (2)\SP-68-7354-C_U129 HER-2_20250501.vsi`

- atomic source fingerprint:
  `d30c49213884efd7fb29e5cc15e14a2f03a096b323b347960ab2c76ce3b85ac9`
- atomic source size: 1.86 GB
- four top-level images and 24 flattened resolutions
- label: 8,021 x 9,366
- overview: 18,032 x 9,148
- main: 165,845 x 90,735
- macro: 512 x 184

The import accepts one selected `.vsi` and resolves the expected CellSens ETS
tree. Regression coverage rejects unrelated, ambiguous, missing, and mutated
companions and revalidates the full inventory before conversion.

## Known QuPath crop

- source crop: `x=69790, y=23372, w=11336, h=11040`
- downsample: `1.5`
- output: 7,557 x 7,360
- rendered-RGB policy: JPEG Q95 intermediate, Q93 pyramidal OME-TIFF
- OME-TIFF bytes: `100,782,362` (96.1 MiB)
- minimum sampled SSIM against the QuPath LZW crop: `0.9774494014722576`
- mean sampled SSIM: `0.9832217366512346`
- maximum per-sample mean Delta E00: `2.965478198985154`
- mean Delta E00: `2.44584093349001`
- OME SHA-256:
  `237e1e4624b2e605b996faf9bfa7703f8b15a32874f41159757f0551e1a14646`
- artifact:
  `C:\Users\enkso\AppData\Local\PathLab Forge\managed\31f52d62-173c-4d56-b8ea-221fd7860cbd\artifacts\5ae808e7-68d4-4b95-8eb9-0f7b5eaa35bd`

## QuPath whole-slide reference

The user-provided QuPath **Rendered RGB** export is the size and geometry
reference for the corrected full-slide path:

- file:
  `C:\Users\enkso\Downloads\Test\SP-68-7354-C_U129 HER-2_20250501.vsi - SP-68-7354-C_U129 HER-2.ome.tif`
- output: 82,922 x 45,367, exactly floor-divided from the selected series at 2x
- bytes: `235,517,679` (224.6 MiB)
- tiled BigTIFF/OME-TIFF, JPEG/YCbCr, 512 x 512 tiles and three SubIFDs

The previous lossless Forge artifacts (23.48 GB native and 12.59 GB at 1.5x)
are retained only as historical bug evidence. They are not the current
configuration revision and cannot be approved or uploaded.

## Corrected full 2x VSI artifact

- output: 82,922 x 45,367
- rendered-RGB policy: JPEG Q95 intermediate, Q75 pyramidal whole-slide OME-TIFF
- elapsed from revision creation through package ready: approximately 5 minutes 30 seconds
- OME-TIFF bytes: `266,489,798` (254.1 MiB)
- OME SHA-256:
  `1d0a5abc089ae203a3a1f50af5b338951693cb13343bf3e293595195404b8572`
- derivative bytes: `822,564,910`
- pyramid tiles: `19,360`
- package bytes: `840,110,592`
- package SHA-256:
  `6987098880b81cf6d5166aad3643b4117389f84404556b4fbce039b7a0cc2392`
- artifact revision: `e3c20478-37ea-4c94-aa80-d866fc8b53d1`
- artifact:
  `C:\Users\enkso\AppData\Local\PathLab Forge\managed\31f52d62-173c-4d56-b8ea-221fd7860cbd\artifacts\e3c20478-37ea-4c94-aa80-d866fc8b53d1`
- observed retained artifact workspace: approximately 1.80 GiB
- aggregate QuPath-reference comparison: SSIM `0.9817815912746595`,
  mean Delta E00 `0.7537315658586172`
- one central tissue patch was below the cross-encoder per-patch threshold
  (SSIM `0.9277935687210488`, mean Delta E00 `3.404254559184185`);
  this is reported rather than hidden because QuPath and libvips use
  independent JPEG encoders
- DZI-to-approved-OME comparison: minimum SSIM `0.998449224057469`,
  maximum per-sample mean Delta E00 `1.0221164929579782`

The final revision was explicitly approved. A Q95 full-slide attempt was
rejected by the size guard at 1,876,307,488 bytes, and a Q90 tuning revision
at 1,225,407,306 bytes remained historical and unapproved.

## Local Viewer ingest

- interrupted after `838,860,800` of `840,110,592` bytes
- Viewer was restarted against the same database and storage
- Forge resumed from the server-reported offset and reached `ready_private`
- private slide:
  `36c4994c-26c9-48b0-a740-64de7dba2b4f`
- Viewer dimensions: 82,922 x 45,367
- Viewer artifact revision, manifest hash, source fingerprint, 0.5 coordinate
  scale and physical calibration matched the prepared manifest
- Layer 1 remained visible after refresh with exactly two annotations
- point transform: source `(75000,30000)` to output `(37500,15000)`
- ruler transform:
  source `(69790,23372)-(81126,34412)` to
  output `(34895,11686)-(40563,17206)`
- PathLab annotation export returned HTTP 200 with
  `pathlab-annotations/v1`, one layer and two structured annotations
- visual evidence:
  `C:\Users\enkso\.codex\visualizations\2026\07\29\019fac40-bd0b-76f2-888b-098168a73306\viewer-final-2x-q75.png`

## Corrected defects

| Root cause | Correction | Regression/evidence |
| --- | --- | --- |
| VSI import could accept the wrong ETS inventory or silently continue after companion mutation. | Grouped normalized VSI stem plus expected CellSens directory as one fingerprinted atomic source and revalidate before work. | Wrong, unrelated, missing, and mutated companion tests. |
| Series/configuration changes could retain mismatched artifact paths and approval. | Added immutable configuration and artifact revisions; only the exact current revision can be approved/uploaded. | Artifact identity and stale approval tests; changing native to 1.5 removed the upload action. |
| Restart re-inspection reset a persisted crop/downsample to full 1x. | Preserve a valid selected-series configuration during re-inspection and hydrate series during bootstrap. | Forge library API regression preserves 1.5, x/y, width, and height. |
| A structurally valid 512 x 512 or near-blank derivative could pass. | Added dimension-aware preview variance/range rejection plus complete pyramid coverage and deterministic edge/center samples. | Near-blank large-derivative regression. |
| libvips received an unsupported `--keep` option. | Removed unsupported CLI flags. | Real crop and full conversions. |
| A partial OME output used an unrecognized `.ome.btf` suffix. | Use the recognized `.ome.tif` suffix while retaining BigTIFF writer options. | Real crop and full conversions. |
| Failed artifacts exposed a package download action. | Only validated READY/APPROVED revisions expose their package. | Browser failure-state verification. |
| Bio-Formats output could decode the micrometre unit as `�m`. | Force UTF-8 child output and normalize accepted micrometre spellings to `µm`. | Metadata parser regression and real manifest. |
| Lossless LZW was used for the rendered RGB OME-TIFF and the final pyramidal rewrite, producing 12.59-23.48 GB artifacts from a 1.86 GB VSI/ETS source. | Match QuPath's rendered-RGB workflow: JPEG Q95 at bounded Bio-Formats rendering, adaptive pyramidal JPEG Q75 for billion-pixel whole slides and Q93 for dense crops, plus a final artifact-size guard. | The QuPath reference is 224.6 MiB; the corrected crop is 96.1 MiB and passes the stricter sampled quality gates. A deliberately attempted Q95 whole-slide revision was rejected at 1.88 GB. |
| Output geometry used rounding, yielding a one-pixel mismatch at 2x. | Use floor division for output width and height, matching QuPath. | `165845 x 90735` now produces exactly `82922 x 45367`. |
| Q85 DZI exceeded the Delta E00 acceptance threshold, while Q90 fell below the strict per-sample SSIM threshold on low-variance edge tiles (`0.958673` minimum). | Use deterministic DZI JPEG Q95 independently of the compact OME-TIFF codec. | The approved full 2x DZI measured `0.998449` minimum SSIM and `1.022117` maximum sampled mean Delta E00. |
| Exhaustively decoding every one of 77,032 tiles made validation unnecessarily slow. | Keep complete signature, coverage, size, and hash checks; decode deterministic corner/center samples at every level. | DZI structure/blank/corruption tests and full native evidence. |
| Desktop upload could time out during finalization and could not continue an interrupted active ingest. | Use a bounded 24-hour final chunk timeout, HEAD-based offset resume, idempotent Viewer finalization recovery, and a final ingest-status read when all bytes already arrived. | Pairing service, prepared-ingest contract tests, and interrupted local upload exercise. |
| Windows proxy selection intercepted loopback Viewer pairing, then Java attempted an unsupported HTTP/2 cleartext upgrade against Uvicorn. | Force `Proxy.NO_PROXY` and HTTP/1.1 for scoped desktop Viewer traffic. | Pairing changed from proxy 400 and Uvicorn invalid-request 400 to successful device approval/exchange and resumed 840 MB ingest. |
| Cropped OME-TIFF conversion was routed through a Bio-Formats rewrite that later failed libvips tiled reads. | Crop and resize OME-TIFF sources directly through bounded libvips rendering before the pyramidal rewrite. | Cancel/restart acceptance completed a 2,730 x 2,730 crop with 59 DZI tiles and a valid package. |

## Automated verification

- Forge Java suite: passed.
- Forge frontend Vitest: passed.
- Forge production bundle budget: passed; app 27.7 KB, React 193.8 KB,
  icons 34.1 KB, OpenSeadragon 342.4 KB.
- Viewer backend pytest: passed with two expected skips.
- Viewer Ruff: passed.
- Viewer frontend lint: passed.
- Viewer Vitest: 31 files and 222 tests passed.
- Viewer production build: passed.
