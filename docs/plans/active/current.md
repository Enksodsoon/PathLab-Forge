# Active Task — Evidence Challenger AI Model v1

Approved by the product owner on 2026-08-02. This task supersedes the completed
BRACS data-preparation milestone. PIVOT remains a non-AI spatial-navigation
baseline and is not the primary educational product.

## Goal

Build a resource-feasible, reproducible AI model that learns from the official
BRACS ROI release and can analyze an unannotated image or whole slide without
requiring new human annotations.

## Implemented model

- two deterministic views per ROI: global context and center detail;
- official patient-disjoint train/validation/test partitions;
- ImageNet-pretrained MobileNetV3-Small 1024-D visual representation;
- class-balanced multinomial classifier selected using validation macro F1;
- validation-only temperature calibration and low-confidence review threshold;
- TorchScript artifact with checksum-verified standalone inference;
- ordinary-image and bounded SVS/OME-TIFF tile inference;
- fine seven-class and coarse BT/AT/MT outputs.

## Research comparison

The untouched 570-ROI, 30-patient test split is compared with:

1. majority-class baseline;
2. RGB mean, standard deviation, and histogram logistic baseline;
3. pretrained deep-feature transfer model.

Reported outcomes include accuracy, balanced accuracy, macro/weighted F1,
top-2 accuracy, coarse-group accuracy, confusion matrices, per-class measures,
selective coverage, and patient-cluster bootstrap confidence intervals.

## Fixed boundaries

- Research and education only; no clinical or diagnostic claim.
- Raw BRACS pixels, derived views, and trained artifacts remain outside Git.
- The official test split is not used for hyperparameter selection or calibration.
- A low-confidence result asks for human review; it is not converted into a
  fabricated answer.
- WSI aggregation is a bounded evidence map, not a validated slide diagnosis.
- No cloud spending, upload, merge, production release, or deployment is implied.

## Acceptance gates

1. All 4,539 ROI records produce two valid 224×224 lossless views.
2. No patient crosses train, validation, or test.
3. Model selection and calibration use validation only.
4. The deep model materially exceeds majority and color baselines on test.
5. TorchScript probabilities match the research classifier numerically.
6. Image inference succeeds on a real BRACS image.
7. Tiled inference succeeds on a real BRACS SVS.
8. Focused Python, static, and full Forge regression checks pass.
9. Exact artifacts, hashes, metrics, environment, and limitations are recorded.
