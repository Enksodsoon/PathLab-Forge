# BRACS annotation-free WSI MIL acceptance — 2026-08-02

## Outcome

The annotation-free whole-slide pipeline is fully functional and reproducible:
it built a complete patient-safe development cohort, trained a fixed
validation-only MIL grid, selected a checksum-addressed TorchScript model, and
ran end-to-end inference on a real unannotated SVS with ranked spatial evidence.

The model **did not pass the predefined research advancement gates**. Validation
macro F1 was 0.4169 rather than 0.65, and coarse BT/AT/MT accuracy was 0.6765
rather than 0.80. The locked test cohort was therefore not downloaded, loaded,
or evaluated. This is an honest negative result: the pipeline is usable for
continued research, but this model is not ready for product integration or a
slide-level performance claim.

Local artifacts, deliberately outside Git:

```text
D:\PathLabData\BRACS\models\pathlab-bracs-kaiko-wsi-full-development-grid-v1
```

## Dataset and feature cohort

- The official remote inventory contains 547 WSIs: 392 train, 68 validation,
  and 87 locked test slides.
- The patient-67 overlap correction produces patient-disjoint splits.
- The verified development feature cohort contains 460 WSIs from 158 patients:
  392 train and 68 validation.
- Development source size: 898,381,186,860 bytes (836.68 GiB).
- Feature bags: 42,346,948 bytes, 58,555 tile vectors, 384 dimensions, 30–128
  tiles per slide, and 127.293 mean tiles per slide.
- Annotation-free sampling is deterministic, bounded to 128 tissue tiles, and
  normalized to 0.5 micrometres per pixel.
- Development inventory SHA-256:
  `6978cb4242d9196493b8c82c12df77c4aef9503a10cefb1ae74a5652cf13801d`.
- Canonical bag manifest SHA-256:
  `9867bc04541468883860d6bca2b4b75f9cac7f5a6b936e70d0ad83260ade9d22`.

The encoder is Kaiko ViT-S/16 release 0.0.1 under its non-commercial research
license. Weight SHA-256 is
`4a117a8138420036ef2319122c8cdc5f7c43ef3f621960a62770c8a920accb0e`;
hub definition SHA-256 is
`b0f5dd8600126c505870e14c548fc94ee6963717e6ca080453ee12bd64a09e6a`.

## Validation-only model selection

All five candidates used seed 20260802, patient-balanced/class-weighted
training, deterministic PyTorch algorithms, early stopping with patience 25,
and validation-only calibration and review-threshold selection.

| Candidate | Macro F1 | Accuracy | Coarse accuracy | ECE | Best epoch |
|---|---:|---:|---:|---:|---:|
| h128-lr1e3-wd1e4 | 0.4169 | 0.5000 | 0.6765 | 0.1100 | 7 |
| h64-lr1e3-wd1e4 | 0.4128 | 0.4706 | 0.7206 | 0.1498 | 13 |
| h256-lr1e3-wd1e4 | 0.3750 | 0.4559 | 0.6912 | 0.1297 | 28 |
| h128-lr3e4-wd1e4 | 0.3736 | 0.4706 | 0.6765 | 0.1040 | 7 |
| h128-lr1e3-wd1e3 | 0.3644 | 0.4412 | 0.6765 | 0.1118 | 9 |

The locked ranking selected `h128-lr1e3-wd1e4`. Its validation results were:

- accuracy 0.5000, balanced accuracy 0.4227, macro F1 0.4169, weighted F1
  0.4649, and top-2 accuracy 0.5882;
- coarse accuracy 0.6765 and coarse macro F1 0.5307;
- 10-bin expected calibration error 0.1100;
- patient-bootstrap 95% intervals: accuracy 0.3375–0.6404, macro F1
  0.2479–0.5108, and coarse accuracy 0.5218–0.8050;
- mean-pool logistic macro F1 0.3333, so MIL improved by 0.0836;
- validation-derived threshold 0.348481 accepted 35/68 slides (51.47%
  coverage) at 65.71% accepted accuracy and referred 33 slides for review.

Selection-record schema v2 verifies the validation-grid, selected-candidate,
model, configuration, evaluation, and mean-pool-baseline hash chain. Selected
TorchScript SHA-256 is
`d03391529516a5f7e6ed4fdcb4c6b988214150c9b8daeb442bac3790f47021e3`.

## Advancement-gate result

| Gate | Required | Observed | Result |
|---|---:|---:|---|
| All reported metrics finite | true | true | pass |
| Seven-class macro F1 | >= 0.65 | 0.4169 | fail |
| Coarse accuracy | >= 0.80 | 0.6765 | fail |
| Macro F1 exceeds mean-pool | > 0.3333 | 0.4169 | pass |
| Review accuracy target | >= 0.65 | 0.6571 | pass |
| Selective coverage | >= 0.30 | 0.5147 | pass |

`advance_to_locked_test` is false. Direct filesystem and artifact checks found:

- 0/87 locked-test feature bags;
- no durable test-attempt lock;
- no test prediction or evaluation artifact;
- `test_loaded: false` in the grid and selection records.

The untouched 87-slide, 31-patient test set remains available for a future
model that first passes every validation gate. No test metric is reported.

## Real unannotated-slide inference

The selected model analyzed the real unannotated fixture
`BRACS_1003718.svs` (17,135×11,733 pixels, 0.2524 micrometres per pixel) using
128 tissue tiles in 6.07 seconds.

- Source SHA-256:
  `2165e44a3138e03fb3529d35bbb4d0ad667a072e4c329d6edfbbe0847b76e4f2`.
- Predicted fine label: N; coarse group: BT.
- Confidence: 0.330417.
- Review required because confidence was below the validation threshold.
- The result includes calibrated seven-class probabilities and 128 ranked tile
  coordinates with attention and predicted-class contribution.
- Selection provenance reports `integrity_verified: true` and preserves
  `advance_to_locked_test: false`.

This functional result proves raw-slide execution, not diagnostic validity.
Spatial contributions are model evidence, not human-verified morphology or
causal ground truth.

## Runtime and resource boundary

- Windows 11, Python 3.12.13, PyTorch 2.9.0 CPU, scikit-learn 1.8.0.
- Six deterministic PyTorch threads on an Intel Core i5-12600-class host.
- Selected-candidate training/evaluation runtime: 37.37 seconds after cached
  feature generation.
- Selected-candidate recorded working set: approximately 400 MiB; private usage
  approximately 1.15 GiB.
- The full feature cohort compresses about 836.68 GiB of source WSIs to 40.39
  MiB of bags, a 21,214.8:1 source-to-feature storage ratio.

## Verification

The completed worktree was verified with:

```powershell
build\ai-training-venv\Scripts\python.exe -m unittest discover -s ai-training\tests -v
build\ai-training-venv\Scripts\ruff.exe check ai-training
build\ai-training-venv\Scripts\ruff.exe format --check ai-training
build\ai-training-venv\Scripts\python.exe -m compileall -q ai-training\src
.\gradlew.bat test
pnpm test
pnpm build
.\scripts\verify-repo.ps1
```

Measured results:

- 31/31 Python data/model/inference tests passed in 2.14 seconds;
- Ruff static checks passed and all 19 AI Python files were formatted;
- Python source compilation passed;
- Gradle `test` completed successfully;
- 5/5 frontend test files and 35/35 tests passed;
- TypeScript compilation and the Vite production build passed;
- the frontend bundle budget passed (`app.js` 76,718 bytes, icons chunk
  52,124 bytes, React chunk 193,794 bytes, OpenSeadragon chunk 342,393 bytes);
- repository policy and `git diff --check` passed.

## Known limitations and next research direction

The seven-class model remains weak, particularly for subtle neighboring lesion
classes, and its patient-bootstrap uncertainty is wide. The MIL improvement
over mean pooling is not statistically secure because its paired 95% interval
crosses zero. A useful next experiment must improve representation/aggregation
or supervision while preserving this exact patient-safe validation protocol;
the gates must not be lowered and the test set must remain sealed until all of
them pass.
