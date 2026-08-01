# PathLab AI training workspace

This workspace prepares public pathology data for PathLab Evidence Challenger.
It is deliberately separate from the Java desktop runtime: Forge will eventually
consume validated model artifacts, not Python training dependencies or raw WSIs.

## BRACS access

BRACS provides 547 labelled `.svs` WSIs from 189 patients and an XLSX summary.
The official download page requires a named account. Register personally at:

- https://www.bracs.icar.cnr.it/registration/
- https://www.bracs.icar.cnr.it/rules/

Do not place credentials or raw data in this repository. Download the archive to
an external data volume, for example:

```text
D:\PathLabData\BRACS\raw\
  Whole Slide Image Set\
    Train\...
    Validation\...
    Test\...
  BRACS-summary.xlsx
```

The exact summary filename may differ. Pass its path explicitly.

## Install

From `ai-training`:

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[wsi]"
```

The `wsi` extra installs `tiffslide` for bounded decoding and tissue QC. Real
training preparation must use decoded QC; header-only mode exists solely for
small deterministic tests.

## Prepare the complete dataset

```powershell
.\.venv\Scripts\pathlab-ai-data.exe prepare-bracs `
  --raw-root "D:\PathLabData\BRACS\raw" `
  --summary "D:\PathLabData\BRACS\raw\BRACS-summary.xlsx" `
  --output-root "D:\PathLabData\BRACS\prepared\bracs-clean-v1"
```

Full mode fails unless it finds the published dataset contract:

- 547 unique WSIs;
- 189 unique patients;
- official patient-disjoint train/validation/test counts;
- official seven-class distribution;
- valid `.svs` TIFF signatures and unique SHA-256 content;
- decodable pyramids, minimum dimensions and non-empty tissue thumbnails.

The raw data is never modified or copied. The output contains:

```text
bracs-clean-v1/
  manifest.csv
  checksums.sha256
  dataset_manifest.json
  DATASET_CARD.md
  splits/
    train.csv
    validation.csv
    test.csv
```

Every slide path is relative to `--raw-root`. The manifest includes slide and
patient IDs, normalized labels, official split, file identity, decoded dimensions,
pyramid levels, physical scale when available, tissue fraction and QC status.

## Tests

From the repository root:

```powershell
$env:PYTHONPATH="$PWD\ai-training\src"
python -m unittest discover -s ai-training\tests -v
```

Tests synthesize tiny BRACS-shaped containers in temporary directories. No WSI
fixture or patient-derived file is committed.

## Research boundary

Only slide-level diagnostic labels enter the weakly supervised training manifest.
BRACS ROI images and QuPath annotations are intentionally excluded and reserved
for held-out evidence-localization evaluation. An entirely unlabeled local slide
may later be explored by a model, but it cannot supply objective grading truth.
