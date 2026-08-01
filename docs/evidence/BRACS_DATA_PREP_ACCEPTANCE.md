# BRACS dataset preparation acceptance — 2026-08-02

## Result

The authorized official `BRACS_RoI/latest_version` release was downloaded from
the BRACS anonymous FTP endpoint after named registration. All 4,539 ROI PNGs
were cleaned and indexed successfully. No anonymous mirror, credential bypass,
or third-party repackaging was used.

Raw data location (outside Git):

```text
D:\PathLabData\BRACS\raw\BRACS_RoI\latest_version
D:\PathLabData\BRACS\raw\BRACS.xlsx
```

Train-ready metadata location:

```text
D:\PathLabData\BRACS\prepared\bracs-roi-clean-v1
```

## Real-data evidence

- 4,539 files and 55,569,695,523 bytes matched the official FTP inventory.
- All 4,539 files verified as RGB PNGs; minimum observed side was 127 px.
- SHA-256 was computed for every image; duplicate-content images: 0.
- 3,657 train, 312 validation, and 570 test ROIs matched the official folders.
- 281/37/69 WSIs and 106/15/30 patients contributed to train/validation/test.
- Patient overlap between every split pair: 0.
- Every ROI label agreed with both its filename and class folder.
- Every ROI linked to a WSI and patient in the official `BRACS.xlsx` summary.
- Manifest SHA-256:
  `6b0a633121ee1b1b811ab1265978b8c70ac9be960b147db1c89347d6d58fca20`.

Exact class totals:

| N | PB | UDH | FEA | ADH | DCIS | IC |
|---:|---:|---:|---:|---:|---:|---:|
| 484 | 836 | 517 | 756 | 507 | 790 | 649 |

The current official download page declares CC BY-NC 4.0. The raw pixels are
not copied into the prepared output or repository.

## Implemented contract

- Official ROI filename, split, and seven-class folder validation.
- WSI and patient linkage through the `WSI_Information` spreadsheet sheet.
- Exact official file, class, split, WSI, and patient distribution gates.
- Patient-disjoint split preservation and independent leakage detection.
- PNG CRC verification, dimensions, mode, byte size, and full SHA-256 identity.
- Duplicate content rejection across every split.
- Atomic manifest generation without modifying or copying raw pixels.
- CSV and JSONL training manifests plus checksum-addressed provenance.

The optional 547-slide WSI cleaner remains available, but the official WSI
release is approximately 984.11 GiB. The 51.75 GiB ROI release is the feasible
initial supervised training source; unannotated WSIs can be tiled at inference.

## Verification

```powershell
build\ai-training-venv\Scripts\python.exe `
  -m unittest discover -s ai-training\tests -v
```

Result: 13 tests passed. Coverage includes decoded WSI fixtures, deterministic
manifests, spreadsheet normalization, invalid containers, duplicate content,
folder/filename disagreement, incomplete distributions, and patient leakage.

The real-data command completed successfully:

```powershell
python -m pathlab_ai_data prepare-bracs-roi `
  --raw-root "D:\PathLabData\BRACS\raw\BRACS_RoI\latest_version" `
  --summary "D:\PathLabData\BRACS\raw\BRACS.xlsx" `
  --output-root "D:\PathLabData\BRACS\prepared\bracs-roi-clean-v1"
```

## Research boundary

This acceptance proves dataset integrity and leakage-safe manifests. It does not
prove model accuracy. Training must retain these official patient-level splits,
report seven-class and coarse-group metrics, compare against non-AI and standard
classifier baselines, and keep the test split untouched until final evaluation.
