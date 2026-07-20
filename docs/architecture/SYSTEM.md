# PathLab Forge System Architecture

## Goal

Convert supported WSI datasets locally into standardized RGB OME-TIFF and DZI assets, process them in batch, and upload prepared packages to PathLab Viewer without server-side WSI conversion.

## Pipeline

```text
files/folders
 -> dataset discovery
 -> companion-file grouping
 -> reader inspection
 -> series selection
 -> full slide or crop
 -> downsample
 -> standardized RGB OME-TIFF
 -> validate OME-TIFF
 -> generate DZI
 -> validate local DZI preview
 -> build .plslide
 -> resumable upload
 -> poll server import
 -> ready_private
```

## Architectural boundaries

Use focused interfaces:

```text
SourceDiscovery
SlideReader
SeriesInspector
RegionRenderer
OmeWriter
DziGenerator
PreparedPackageBuilder
PackageValidator
JobRepository
BatchScheduler
ViewerClient
CredentialStore
```

Core batch/domain code must not store QuPath or vendor-specific objects.

## Batch model

One `Batch` contains ordered `SlideJob` records. Each job owns source identity, chosen series, crop, downsample, render profile, output paths, package hash, upload identity, state, retry metadata and timestamps.

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
convert A -> upload A -> clean A -> convert B
```

## Job states

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
SERVER_IMPORTING
READY_PRIVATE
PUBLISHED
PAUSED
CANCEL_REQUESTED
CANCELLED
FAILED_RETRYABLE
FAILED_PERMANENT
SKIPPED
```

State transitions must be explicit and tested.

## Reader strategy

Reader-specific code lives behind adapters. Intended order:

1. fastest compatible pathology reader;
2. Bio-Formats fallback for formats such as VSI;
3. native TIFF/OME handling where appropriate;
4. structured unsupported/incomplete result.

A VSI dataset may require `.ets` companions. Missing companions must block conversion with a clear error.

## Rendering contract

Default profile: `PATHOLOGY_STANDARD`.

- interleaved 8-bit RGB;
- ICC to sRGB when present;
- no subjective brightness/contrast change;
- deterministic 16-to-8-bit conversion when required;
- white alpha background;
- physical scale preserved when known;
- no invented scale.

DZI is generated from the newly created OME-TIFF so both outputs represent exactly the same crop, downsample and rendering.

## Platform strategy

Shared core targets Java 17-compatible language/bytecode. Modern and legacy builds may use different reader/native adapters but must produce the same `.plslide` contract.

Do not spread QuPath-version-specific classes through the core.
