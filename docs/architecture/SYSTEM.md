# PathLab Forge System Architecture

## Goal

Convert supported WSI datasets locally into standardized RGB OME-TIFF and PathLab Viewer-compatible DZI assets, process slides in a persistent batch queue, and upload a prepared package without asking the server to decode or convert a WSI.

## Current PathLab Viewer boundary

PathLab Viewer is no longer a flat slide uploader. The current server has:

- nested folders, collections, saved views and restorable Trash;
- bounded metadata-only library queries and status polling;
- persisted storage reservations and derivative measurements;
- cached private thumbnails;
- a serial worker with heartbeat, stale recovery and capacity monitoring;
- privacy-reviewed publication grants, hardlinked delivery aliases and multi-slide sharing;
- a private administrator annotation workspace.

Forge therefore produces one derivative that can enter this existing lifecycle. It does not create a second library, share system, publication path or worker service.

## End-to-end pipeline

```text
files/folders
 -> dataset discovery
 -> companion-file grouping
 -> reader inspection
 -> series selection
 -> full slide or crop
 -> downsample
 -> standardized RGB OME-TIFF kept locally
 -> validate OME-TIFF
 -> generate Viewer-compatible DZI + thumbnail
 -> validate local OpenSeadragon preview
 -> build .plslide package
 -> negotiate Viewer capabilities
 -> reserve an Unfiled/folder-aware library slide
 -> resumable capability-negotiated upload
 -> poll existing Viewer states
 -> ready_private
 -> open existing browser preview
```

## Prepared package v2

Package version 2 contains only files needed by PathLab Viewer, in canonical order:

```text
manifest.json
manifest.sha256
inventory.ndjson
derivative/slide.dzi
derivative/slide_files/<level>/<column>_<row>.jpg
derivative/thumbnail.jpg
```

The standardized OME-TIFF remains local and is not included in the package.

The output must match the current Viewer derivative contract:

```text
DZI tile size: 512
DZI overlap: 1
DZI JPEG quality: adaptive Q85/Q90/Q95 under the strict visual gate
Thumbnail: thumbnail.jpg
Thumbnail longest edge: 640
Thumbnail JPEG quality: 82
```

Generate the DZI and thumbnail from the newly written OME-TIFF so the crop,
dimensions, downsample and RGB rendering are identical. The selector evaluates
at least 32 deterministic tissue/background/edge/seam regions with the same
libvips JPEG settings used by `dzsave`; it fails closed if Q95 does not pass.

Source verification continues asynchronously after metadata and thumbnail become
available. Conversion remains blocked until the complete companion inventory is
verified. Preview readers share a 512 MiB global cache, allow at most two live
sessions, and close explicitly on eviction and shutdown.

## Architectural interfaces

Use focused interfaces:

```text
SourceDiscovery
DatasetGrouper
SlideReader
SeriesInspector
RegionRenderer
OmeWriter
DziGenerator
DerivativeValidator
PreparedPackageBuilder
JobRepository
BatchScheduler
ViewerCapabilitiesClient
ViewerUploadClient
CredentialStore
```

Core batch/domain code must not store QuPath, Bio-Formats, OpenSlide, libvips, JavaFX or HTTP client objects.

## Batch model

One `Batch` contains ordered `SlideJob` records. Each job owns:

- source dataset identity and companion-file inventory;
- selected series;
- crop and downsample;
- render profile;
- local OME, derivative and package paths;
- measured derivative bytes/file count/tile count;
- package hash;
- target Viewer URL and optional folder ID;
- server slide ID;
- tus resume identity;
- state, retry metadata and timestamps.

Default concurrency:

```text
conversion = 1
upload = 1
```

When resources permit:

```text
upload slide A while converting slide B
```

Low-resource mode:

```text
convert A -> package A -> upload A -> confirm import -> clean A -> convert B
```

## Local job states

Forge states describe the user workflow and do not need to mirror Viewer database states one-to-one:

```text
PENDING
INSPECTING
NEEDS_REVIEW
READY
EXPORTING_OME
VALIDATING_OME
GENERATING_DZI
VALIDATING_DZI
PACKAGING
READY_TO_UPLOAD
UPLOADING
SERVER_PROCESSING
READY_PRIVATE
PUBLISHED
PAUSED
CANCEL_REQUESTED
CANCELLED
FAILED_RETRYABLE
FAILED_PERMANENT
SKIPPED
```

`SERVER_PROCESSING` may represent Viewer `queued`, `validating` or `converting`. The job stores the latest server state separately for diagnostics.

State transitions must be explicit and tested.

## Reader strategy

Reader-specific code lives behind adapters. Intended order:

1. fastest compatible pathology reader;
2. Bio-Formats fallback for formats such as VSI;
3. native TIFF/OME handling where appropriate;
4. structured unsupported or incomplete result.

A VSI dataset may require `.ets` companions. Missing companions must block conversion with a clear error and must not stop unrelated jobs in the batch.

## Rendering contract

Default profile: `PATHOLOGY_STANDARD`.

- interleaved 8-bit RGB;
- ICC transformed to sRGB when present;
- source values treated as sRGB when no profile exists;
- deterministic 16-to-8-bit conversion when required;
- white alpha background;
- no subjective brightness/contrast adjustment;
- physical scale preserved when known;
- no invented scale.

A display-adjusted profile remains clearly labelled as unsuitable for quantitative pixel analysis.

## Viewer library integration

Forge may request an optional target folder during reservation. Without one, the slide enters Unfiled.

Forge does not:

- create or edit collections during upload;
- publish a slide automatically;
- activate folder/collection shares;
- upload annotations;
- mark privacy review as passed.

After `ready_private`, the current Viewer UI manages metadata, collections, sharing, annotations, Trash and publication.

## Platform strategy

Shared core targets Java 17-compatible language and bytecode. Modern and legacy builds may use different reader/native adapters but must produce the same package contract.

Do not spread QuPath-version-specific classes through the core. A platform is supported only after a packaged artifact passes the documented compatibility matrix on that actual operating system.
