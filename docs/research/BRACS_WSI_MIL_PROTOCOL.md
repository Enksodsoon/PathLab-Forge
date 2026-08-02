# BRACS unannotated-WSI MIL protocol

## Research question

Can an annotation-free, pathology-pretrained feature extractor plus weakly
supervised multiple-instance learning classify a BRACS whole slide from only its
slide-level diagnosis, while also returning spatial evidence useful for teaching?

This is a research and education experiment, not a clinical-diagnosis claim.

## Data and leakage control

- Source: the official 547-slide BRACS WSI release (189 patients, seven classes).
- No ROI coordinates, cell labels, segmentation masks, or new human annotation are
  used to sample slide tiles.
- BRACS patient 67 occurs in both the published training and validation rows. The
  corrected model split moves every patient-67 slide to validation. The official
  test patients remain untouched.
- Expected corrected split: 392 train, 68 validation, and 87 test slides with no
  patient crossing a model split.
- Selection and calibration use train/validation only. Test is loaded once after
  the model family and operating threshold are frozen.

The first resource-bounded development cohort contains 15 train and 5 validation
slides per class (140 slides, 85 patients, about 157 GiB). It deliberately chooses
the smallest source files within each class and split. That size bias makes it a
pipeline/model-development cohort, not a population-performance estimate.

## Development sequence and amendment

The 140-slide pilot completed before the full-development run. Its selected MIL
model reached 0.4211 validation macro F1 and 0.6857 coarse accuracy, exceeded the
mean-pool baseline by 0.0949 macro F1, and autonomously covered 11.43% of slides.
It failed the fixed accuracy and coverage gates, so the guarded workflow did not
construct, download, or evaluate the locked WSI test cohort.

An exploratory validation-only comparison of mean/statistical pooling, RBF SVM,
and weak tile-label logistic models did not beat the pilot MIL. The evidence
therefore motivated a data amendment rather than test tuning: repeat the unchanged
five-candidate MIL grid using all 392 patient-safe training slides and all 68
validation slides. This removes the pilot's source-file-size selection bias. The
original 35 validation slides have already been observed during development, so
the expanded validation result is a development estimate, not an untouched
generalization estimate. The locked 87-slide/31-patient test remains the only
eligible final estimate and stays unavailable unless every original gate passes.

## Fixed pipeline

1. Build a tissue mask from a 2048-pixel thumbnail without annotations.
2. Sample at most 128 spatially distributed tissue tiles per slide at 0.5 µm/px.
3. Resize each tile to 224 px and encode it with Kaiko ViT-S/16 (384 dimensions).
4. Train a class-weighted gated-attention MIL head using slide labels only.
   Within training, inverse slide-count patient weights prevent patients with
   multiple slides from dominating; the mean-pool baseline uses the same weights.
5. Select the checkpoint by validation macro F1 with early stopping.
6. Fit temperature and the review threshold using validation predictions only.
7. Return seven-class probabilities, BT/AT/MT grouping, review status, and ranked
   tile coordinates with normalized attention weights and exact additive
   predicted-class contributions.
8. If every advancement gate passes, freeze the selected TorchScript checksum and
   evaluate that artifact on the locked test exactly once without retraining.

The guarded completion script performs real unannotated-slide inference for every
selected validation model. It reads the machine-generated gate decision and only
constructs or downloads the locked test cohort when every gate is true. A failed
gate therefore needs no discretionary human decision to keep the test sealed.

Kaiko weights are under a non-commercial research license. Any commercial release
must replace or separately license that encoder.

The frozen encoder identity for this experiment is Kaiko ViT-S/16 release `0.0.1`:

- loader `hubconf.py` SHA-256:
  `b0f5dd8600126c505870e14c548fc94ee6963717e6ca080453ee12bd64a09e6a`;
- `vits16.pth` SHA-256:
  `4a117a8138420036ef2319122c8cdc5f7c43ef3f621960a62770c8a920accb0e`.

Both hashes are verified before encoding and embedded in the canonical bag
manifest and model configuration.

## Parameters to record

| Area | Fixed or reported value |
|---|---|
| Sampling | target MPP, tissue threshold, candidate count, retained tile count |
| Encoder | repository/revision, weight identity, feature dimension, license |
| MIL | hidden dimension, learning rate, weight decay, dropout, tile dropout |
| Selection | seed, maximum epochs, patience, best epoch, validation trajectory |
| Calibration | temperature, confidence threshold, coverage, accepted accuracy |
| Resources | bytes downloaded, extraction/training time, CPU threads, peak memory |
| Provenance | inventory, bag-manifest, TorchScript, and configuration SHA-256 |

## Comparisons

The same patient-safe split and feature bags are used for every comparison:

1. majority-class prediction;
2. mean-pooled Kaiko features with class-balanced logistic regression;
3. gated-attention Kaiko MIL;
4. the existing ROI-trained MobileNet and Kaiko models as domain-shift references,
   not as slide-level competitors.

No test result is used to choose among these methods.

The final evaluator refuses to overwrite an existing test evaluation and records
both the frozen model checksum and the exact combined bag-manifest checksum.
The production BRACS CLI also refuses a locked-test manifest unless it contains
exactly 87 slides, 31 patients, and all seven diagnostic classes.
It additionally requires the passing validation-selection record and verifies the
selected TorchScript, model configuration, evaluation, and mean-pool comparator
checksums before reading locked-test predictions. The selection record also pins
the complete validation-grid checksum. The evaluator cross-checks the selected
candidate, every advancement gate, the final decision, and the selected row's
four artifact hashes against that grid instead of trusting a mutable pass flag.
Immediately before production test bags are loaded, it atomically creates a durable
attempt lock. Concurrent calls and retries after an interrupted exposure therefore
fail closed and require an explicit research-integrity audit.
Tile contributions explain the model computation; they are not asserted to be
human-verified morphology, causal evidence, or annotation ground truth.

## Outcomes and gates

Primary outcome: patient-aware seven-class macro F1. Secondary outcomes: accuracy,
balanced accuracy, per-class F1, top-2 accuracy, coarse BT/AT/MT accuracy and macro
F1, log loss, calibration, selective coverage/accuracy, confusion matrix, runtime,
and patient-cluster bootstrap 95% intervals.

The development model advances to locked-test evaluation only if it:

- exceeds the validation mean-pool baseline in macro F1;
- reaches at least 0.65 validation seven-class macro F1;
- reaches at least 0.80 validation coarse accuracy;
- meets the 0.65 selective-accuracy target while autonomously covering at least
  30% of validation slides;
- produces finite, normalized probabilities and attention on every slide;
- passes patient-leakage, deterministic sampling, artifact-integrity, and raw-WSI
  inference tests.

Failing a gate is a valid negative result. It triggers a train/validation redesign,
not test-set tuning. A final paper-quality estimate requires a representative or
complete official held-out test set; the size-selected development cohort alone is
insufficient.
