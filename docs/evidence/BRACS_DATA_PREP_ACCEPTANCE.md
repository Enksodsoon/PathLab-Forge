# BRACS dataset preparation acceptance — 2026-08-01

## Result

The reproducible BRACS cleaning and manifest pipeline is implemented and passes
synthetic decoded-WSI acceptance. The real 547-slide dataset has not been cleaned
because the official download requires named user registration and no authorized
BRACS archive or summary spreadsheet is present on this computer.

No anonymous mirror, credential bypass or third-party repackaging was used.

## Implemented contract

- CSV and XLSX summary parsing with normalized aliases.
- Seven canonical WSI labels and BT/AT/MT folder validation.
- Exact published 547-slide, 189-patient, class, split and per-split class gates.
- Official patient-disjoint split preservation and independent leakage detection.
- Missing/extra inventory, duplicate ID, duplicate SHA-256, label and split checks.
- Classic TIFF/BigTIFF signature validation.
- Bounded `tiffslide` decoding, dimensions, pyramid levels and 512-pixel tissue QC.
- Full SHA-256 identities and relative paths only.
- Deterministic manifest and split files through partial staging and atomic rename.
- No raw WSI, ROI, annotation or generated patch redistribution.

## Focused verification

Environment:

- Python 3.12.13
- `tiffslide` 4.0.0
- `Pillow` 12.3.0

Command:

```powershell
build\ai-training-venv\Scripts\python.exe `
  -W error::DeprecationWarning `
  -m unittest discover -s ai-training\tests -v
```

Result: 10 tests passed, including a runtime-generated 1024 × 1024 BigTIFF/SVS
pyramid with two levels and non-empty tissue measurement. Other tests cover
reproducibility, XLSX normalization, descriptive folder aliases, duplicate
content, incomplete distribution, invalid container, label disagreement and
patient leakage.

Static verification:

```text
ruff check ai-training                  passed
ruff format --check ai-training         passed
python -m compileall                    passed
pip check                               passed
```

## Repository regression

```text
gradlew.bat test                         passed
frontend vitest                          5 files, 35 tests passed
scripts/verify-repo.ps1                 passed
git diff --check                        passed
```

The generated decoded-QC fixture was moved out of the repository to the Windows
temporary directory before the repository policy check. It contained no patient
data and can be discarded.

## Required real-data handoff

1. The product owner registers at the official BRACS site with correct identity.
2. Download the `Whole Slide Image Set` and the supplied summary XLSX to an
   external path such as `D:\PathLabData\BRACS\raw`.
3. Install `ai-training` with its `wsi` dependency extra.
4. Run the exact command documented in `ai-training/README.md`.
5. Do not start feature extraction or model training unless full mode reports
   `validation_level=decoded-wsi-tissue-qc`, 547 clean slides, 189 patients and
   zero rejected slides.

## Honest limitation

This acceptance proves the cleaner and its safety contract, not the condition of
the gated BRACS archive. Real-slide checksums, decoded dimensions, tissue fractions
and dataset-manifest hash do not exist until the authorized archive is supplied.
