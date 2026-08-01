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
| Validation | 10 | 11 | 9 | 6 | 8 | 9 | 12 | 67 | 25 |
| Test | 7 | 16 | 9 | 11 | 12 | 12 | 20 | 85 | 31 |
| Total | 44 | 147 | 74 | 41 | 48 | 61 | 132 | 547 | 189 |

The cleaner treats these values as a fail-closed completeness contract in normal
full-dataset mode.

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

The implementation and synthetic-fixture validation can be completed without
access to patient-derived pixels. Real BRACS preparation remains unexecuted until
the product owner completes official registration and places the authorized raw
archive and summary spreadsheet outside Git.
