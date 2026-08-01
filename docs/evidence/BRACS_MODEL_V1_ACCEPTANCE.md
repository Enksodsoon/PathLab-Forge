# BRACS Evidence Challenger model v1 acceptance — 2026-08-02

## Outcome

PathLab BRACS MobileNetV3-Small Transfer v1 trained successfully and produced a
checksum-addressed TorchScript artifact. It is functional for ordinary images
and bounded unannotated WSI tiling. It is for research and education, not clinical
diagnosis.

Local artifact directory, deliberately outside Git:

```text
D:\PathLabData\BRACS\models\pathlab-bracs-mobilenet-v1
```

TorchScript SHA-256:

```text
5177f957497e706bb8c772f7192c5c194083aa2cca701971bce36a4155b3d1df
```

## Dataset and protocol

- 4,539 BRACS ROIs from 387 WSIs and 151 patients.
- 3,657/312/570 ROI train/validation/test split.
- 106/15/30 patient train/validation/test split with zero overlap.
- Two lossless 224×224 views per ROI; 9,078 views, 980,045,660 bytes.
- MobileNetV3-Small ImageNet weights frozen as a 1024-D encoder.
- Class-balanced multinomial head; C chosen from 0.01, 0.1, 1, and 10 using
  validation macro F1 only.
- Temperature and review threshold chosen using validation only.
- Test evaluated after selection and calibration.
- Confidence intervals use 500 patient-cluster bootstrap resamples.

## Untouched test result

| Measure | Deep model | Color baseline | Majority baseline |
|---|---:|---:|---:|
| Seven-class accuracy | 0.5000 | 0.3386 | 0.1386 |
| Seven-class macro F1 | 0.4980 | 0.3310 | 0.0348 |
| Balanced accuracy | 0.4989 | 0.3385 | 0.1429 |
| Top-2 accuracy | 0.7123 | 0.5509 | 0.2877 |
| Coarse BT/AT/MT accuracy | 0.6702 | 0.6140 | 0.4246 |
| Coarse macro F1 | 0.6579 | 0.5969 | 0.1987 |

Patient-bootstrap 95% intervals:

- accuracy: 0.4347–0.5667;
- macro F1: 0.4332–0.5411;
- coarse accuracy: 0.6053–0.7426.

Per-class F1: N 0.6471, PB 0.3576, UDH 0.2339, FEA 0.7059, ADH 0.2821,
DCIS 0.4294, and IC 0.8302. These weak PB/UDH/ADH results are a material
limitation and justify review/abstention rather than autonomous teaching answers.

The validation-derived confidence threshold was 0.369524. On test it accepted
450/570 ROIs (78.95% coverage) with 57.11% accepted accuracy and sent 120 ROIs
to review. Validation selective accuracy was 65.25%; its reduction on test is
reported rather than hidden.

## Deployment verification

- Exported TorchScript probabilities differed from the research classifier by
  at most 6.7e-08 on the same two real cached views.
- A real BRACS ROI completed end-to-end image inference in 4.70 seconds including
  Python startup, raw PNG decoding, two-view preprocessing, checksum verification,
  model loading, and inference.
- A real 17,135×11,733, three-level BRACS SVS (0.2524 µm/px) was downloaded as a
  local unannotated fixture. Bounded inference sampled 64 locations, retained 14
  tissue tiles, and wrote a complete probability heatmap in 5.19 seconds.
- The real WSI functional run is not included in the reported test accuracy and
  is not presented as a validated slide-level diagnosis.

## Runtime

- Windows, Python 3.12.13;
- PyTorch 2.9.0 CPU and Torchvision 0.24.0 CPU;
- scikit-learn 1.8.0;
- Intel Core i5-12600, six cores/twelve logical processors;
- six inference threads;
- cold feature extraction, training, and evaluation: 92.97 seconds after view
  generation; deterministic cached rerun: 42.15 seconds;
- peak observed training working set: approximately 700 MiB.

## Artifacts

```text
pathlab_bracs_mobilenet_v1.ts  deployable model
model_config.json              labels, calibration, checksum, use boundary
evaluation.json                complete metrics and provenance
test_predictions.csv           per-ROI probabilities and review decision
confusion_matrix.csv            exact seven-class confusion matrix
TRAINING_REPORT.md              concise model card
feature_cache.npz               reproducible deep embeddings
research_classifier.joblib      research-only estimator and scaler
real_wsi_heatmap.json           real-slide functional evidence map
```

## Verification commands

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

Acceptance result: 16 Python/model tests passed; Gradle passed; 5 frontend test
files and 35 tests passed; the production bundle budget passed; and repository
policy passed.

The full evaluation is machine-readable in the local `evaluation.json`. Model
weights and derived patient images are excluded from the public repository.
