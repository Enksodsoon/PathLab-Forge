# Active Task — Unannotated-WSI Evidence MIL v2

Approved by the product owner on 2026-08-02. This task supersedes the completed
BRACS data-preparation milestone. PIVOT remains a non-AI spatial-navigation
baseline and is not the primary educational product.

## Goal

Build and validate a resource-feasible AI model that learns from public BRACS
whole slides using slide labels only, then analyzes a new unannotated WSI and
returns a prediction plus spatial teaching evidence without manual annotation.

## Validated research model

- complete corrected BRACS development split: 392 train and 68 validation slides;
- annotation-free tissue sampling at a fixed physical resolution;
- patient-disjoint splits with the published patient-67 overlap corrected;
- pathology-pretrained Kaiko ViT-S/16 384-D tile representations;
- class-weighted gated-attention multiple-instance learning from slide labels;
- validation-only temperature calibration and low-confidence review threshold;
- TorchScript artifact with checksum-verified standalone inference;
- raw SVS/OME-TIFF inference with ranked tile-coordinate evidence;
- fine seven-class and coarse BT/AT/MT outputs.

## Research comparison

The locked WSI test split will be compared only after validation selection with:

1. majority-class baseline;
2. mean-pooled Kaiko feature logistic baseline;
3. gated-attention Kaiko MIL.

Reported outcomes include accuracy, balanced accuracy, macro/weighted F1,
top-2 accuracy, coarse-group accuracy, confusion matrices, per-class measures,
selective coverage, and patient-cluster bootstrap confidence intervals.

## Fixed boundaries

- Research and education only; no clinical or diagnostic claim.
- Raw BRACS pixels, derived views, and trained artifacts remain outside Git.
- The official test split is not used for hyperparameter selection or calibration.
- A low-confidence result asks for human review; it is not converted into a
  fabricated answer.
- No slide-level performance claim is made until the locked WSI test is evaluated.
- No cloud spending, upload, merge, production release, or deployment is implied.

## Acceptance gates

1. WSI inventory contains all 547 official slides and records the leakage correction.
2. No patient crosses train, validation, or test.
3. Tissue sampling is deterministic, annotation-free, bounded, and MPP-aware.
4. Model selection and calibration use validation only; test remains unavailable.
5. Validation macro F1 is at least 0.65 and exceeds mean-pool; coarse accuracy is
   at least 0.80, and the 0.65 selective-accuracy policy covers at least 30% of
   slides before test evaluation is authorized.
6. TorchScript probabilities and attention are finite and normalized.
7. End-to-end MIL inference succeeds on a real unannotated BRACS SVS.
8. Focused Python, static, and full Forge regression checks pass.
9. Exact artifacts, hashes, metrics, environment, bias, and limitations are recorded.

## Experiment result

The complete corrected 460-slide development cohort was extracted and exactly
verified. Five fixed validation-only candidates were trained. The selected model
reached macro F1 0.4169, coarse accuracy 0.6765, and selective coverage 0.5147.
It beat the mean-pool baseline and passed the review-policy gates, but failed the
fixed macro-F1 and coarse-accuracy gates. The locked 87-slide test set therefore
remains untouched and no slide-level performance claim is authorized.

End-to-end inference nevertheless succeeded on a real unannotated BRACS SVS in
6.07 seconds with checksum-verified selection provenance, calibrated
probabilities, review behavior, and ranked spatial tile evidence. The pipeline
is complete for continued research; this trained model is not approved for
product integration.

The fixed experiment is documented in
`docs/research/BRACS_WSI_MIL_PROTOCOL.md`; measured acceptance evidence is in
`docs/evidence/BRACS_WSI_MIL_ACCEPTANCE.md`.
