# BRACS data preparation contract

## Verified source facts

The official BRACS site reports 547 labelled WSIs, 4,539 labelled ROIs and 189
patients. The accompanying publication defines seven WSI labels and publishes
patient-disjoint reference splits. Download access requires truthful named
registration and login.

Primary references:

- https://www.bracs.icar.cnr.it/
- https://www.bracs.icar.cnr.it/rules/
- https://doi.org/10.1093/database/baac093

## Published WSI distribution

| Split | N | PB | UDH | FEA | ADH | DCIS | IC | WSIs | Patients |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Train | 27 | 120 | 56 | 24 | 28 | 40 | 100 | 395 | 133 |
| Validation | 10 | 11 | 9 | 6 | 8 | 9 | 12 | 65 | 25 |
| Test | 7 | 16 | 9 | 11 | 12 | 12 | 20 | 87 | 31 |
| Total | 44 | 147 | 74 | 41 | 48 | 61 | 132 | 547 | 189 |

The cleaner treats these values as a fail-closed completeness contract in normal
full-dataset mode.

## Published ROI distribution used for initial training

The official `BRACS_RoI/latest_version` server inventory contains 4,539 PNGs from
387 WSIs and 151 patients, totaling 51.75 GiB. Counts are enforced exactly:

| Split | N | PB | UDH | FEA | ADH | DCIS | IC | ROIs | WSIs | Patients |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Train | 357 | 714 | 389 | 624 | 387 | 665 | 521 | 3,657 | 281 | 106 |
| Validation | 46 | 43 | 46 | 49 | 41 | 40 | 47 | 312 | 37 | 15 |
| Test | 81 | 79 | 82 | 83 | 79 | 85 | 81 | 570 | 69 | 30 |
| Total | 484 | 836 | 517 | 756 | 507 | 790 | 649 | 4,539 | 387 | 151 |

`prepare-bracs-roi` validates PNG decoding and dimensions, filename/folder label
agreement, WSI spreadsheet linkage, exact file hashes, duplicate content, and
patient-disjoint splits. Its manifest is the initial train-ready contract.

## Normalized labels

| Code | Name | Coarse source group |
|---|---|---|
| N | Normal | BT |
| PB | Pathological Benign | BT |
| UDH | Usual Ductal Hyperplasia | BT |
| FEA | Flat Epithelial Atypia | AT |
| ADH | Atypical Ductal Hyperplasia | AT |
| DCIS | Ductal Carcinoma In Situ | MT |
| IC | Invasive Carcinoma | MT |

## Cleaning and validation

For every WSI, `pathlab-ai-data prepare-bracs` verifies:

1. one summary record and one `.svs` source file;
2. canonical `BRACS_<number>` identity;
3. agreement among summary label, summary split and folder structure;
4. valid classic-TIFF or BigTIFF byte order/signature;
5. unique full-file SHA-256 identity;
6. decoded dimensions and pyramid levels through `tiffslide`;
7. bounded 512-pixel thumbnail tissue fraction;
8. no patient crossing train, validation and test;
9. exact published dataset distributions.

All output is written to a sibling partial directory and atomically finalized.
The cleaner refuses an existing output directory and never overwrites or deletes
the authorized raw archive.

## Training schema

`manifest.csv` fields:

```text
slide_id, patient_id, label_code, label_name, coarse_group,
split, source_split, relative_path, file_size_bytes, sha256,
roi_count, width, height, level_count, mpp_x, mpp_y,
tissue_fraction, integrity_status
```

Patch extraction must group by `slide_id`, retain `patient_id` for leakage checks,
and use `label_code` only at the WSI/bag level. It must not convert the WSI label
into a patch label.

The preparation stage intentionally does not rewrite pixels or apply stain
normalization. Such transforms can remove diagnostically relevant variation and
will be evaluated later as explicit training augmentations against the untouched,
checksum-addressed source.

## Current handoff status

Registration and official ROI download are complete. All 4,539 files passed the
fail-closed manifest build at `D:\PathLabData\BRACS\prepared\bracs-roi-clean-v1`.
The raw data remains outside Git at `D:\PathLabData\BRACS\raw`.

## Model v1 result

The first leakage-safe transfer model is complete. It uses two deterministic ROI
views, a frozen MobileNetV3-Small encoder, validation-selected class-balanced
logistic classification, temperature calibration, and confidence-based review.
On the untouched 570-ROI/30-patient test split it achieved 0.5000 seven-class
accuracy, 0.4980 macro F1, 0.7123 top-2 accuracy, and 0.6702 coarse BT/AT/MT
accuracy. See `docs/evidence/BRACS_MODEL_V1_ACCEPTANCE.md` for baselines,
confidence intervals, per-class limitations, artifact hashes, and real-SVS
functional evidence.
