# PathLab AI training workspace

This workspace prepares public pathology data for PathLab Evidence Challenger.
It is deliberately separate from the Java desktop runtime: Forge will eventually
consume validated model artifacts, not Python training dependencies or raw WSIs.

## BRACS access

BRACS provides 547 labelled `.svs` WSIs, 4,539 labelled ROI PNGs and an XLSX
summary. The current official download page declares CC BY-NC 4.0 use.
The official download page requires a named account. Register personally at:

- https://www.bracs.icar.cnr.it/registration/
- https://www.bracs.icar.cnr.it/rules/

Do not place credentials or raw data in this repository. Download the archive to
an external data volume, for example:

```text
D:\PathLabData\BRACS\raw\
  BRACS_RoI\latest_version\
    train\...
    val\...
    test\...
  BRACS.xlsx
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

## Prepare the practical ROI training dataset

The current WSI release is approximately 984 GiB. The official latest ROI set is
51.75 GiB and is the initial supervised training source. A model trained on ROI
images can still process a new, unannotated WSI by tiling it during inference.

```powershell
.\.venv\Scripts\pathlab-ai-data.exe prepare-bracs-roi `
  --raw-root "D:\PathLabData\BRACS\raw\BRACS_RoI\latest_version" `
  --summary "D:\PathLabData\BRACS\raw\BRACS.xlsx" `
  --output-root "D:\PathLabData\BRACS\prepared\bracs-roi-clean-v1"
```

Full mode decodes and hashes all 4,539 PNGs, validates exact class/split counts,
links each ROI to its source WSI and patient, rejects duplicate content, and
fails if any patient crosses train, validation, or test. It writes `manifest.csv`,
`manifest.jsonl`, and `provenance.json`; raw pixels are never copied or changed.

## Prepare the optional complete WSI dataset

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

ROI labels provide supervised morphology learning while the official patient-level
split prevents leakage. Unannotated local WSIs are tiled at inference time; tile
predictions are aggregated into an evidence map and are not treated as new ground
truth. WSI-level weak supervision remains an optional later experiment because
the complete source requires roughly 984 GiB before derived training artifacts.

## Train and run the Evidence Challenger model

Install the local CPU runtime from the repository root. The explicit PyTorch CPU
index prevents installation of unnecessary CUDA libraries:

```powershell
build\ai-training-venv\Scripts\python.exe -m pip install `
  torch==2.9.0 torchvision==0.24.0 `
  --index-url https://download.pytorch.org/whl/cpu
build\ai-training-venv\Scripts\python.exe -m pip install `
  "scikit-learn>=1.8,<1.9" "joblib>=1.4,<2"
build\ai-training-venv\Scripts\python.exe -m pip install -e ai-training --no-deps
```

Create the two deterministic, lossless views for each clean ROI using the
bundled Node.js and Sharp runtime:

```powershell
$env:NODE_PATH="C:\Users\enkso\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules"
& "C:\Users\enkso\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe" `
  ai-training\scripts\build_bracs_views.mjs `
  --manifest "D:\PathLabData\BRACS\prepared\bracs-roi-clean-v1\manifest.jsonl" `
  --raw-root "D:\PathLabData\BRACS\raw\BRACS_RoI\latest_version" `
  --output-root "D:\PathLabData\BRACS\derived\mobilenet-v1\views" `
  --concurrency 4
```

Train and evaluate:

```powershell
build\ai-training-venv\Scripts\pathlab-ai-model.exe train-bracs `
  --views-root "D:\PathLabData\BRACS\derived\mobilenet-v1\views" `
  --output-root "D:\PathLabData\BRACS\models\pathlab-bracs-mobilenet-v1" `
  --batch-size 64 --threads 6 --bootstrap-iterations 500
```

Run on an ordinary unannotated image:

```powershell
build\ai-training-venv\Scripts\pathlab-ai-model.exe predict-image `
  --model-root "D:\PathLabData\BRACS\models\pathlab-bracs-mobilenet-v1" `
  --image "C:\path\to\region.png"
```

Run bounded tile inference on an unannotated SVS/OME-TIFF:

```powershell
build\ai-training-venv\Scripts\pathlab-ai-model.exe predict-slide `
  --model-root "D:\PathLabData\BRACS\models\pathlab-bracs-mobilenet-v1" `
  --slide "C:\path\to\slide.svs" `
  --output "C:\path\to\evidence-map.json" `
  --tile-size 1024 --max-tiles 400
```

The v1 model is a frozen ImageNet-pretrained MobileNetV3-Small encoder plus a
class-balanced seven-class head selected on the official validation split. It
uses temperature calibration and a validation-derived review threshold. Its
outputs are educational evidence prompts, not clinical diagnoses.

## Annotation-free WSI MIL development

The v2 research path trains from WSI-level labels only. It uses a deterministic
tissue sampler, Kaiko ViT-S/16 tile features, and a gated-attention MIL head.
Create/validate feature bags first, then select the model without touching test:

```powershell
build\ai-training-venv\Scripts\pathlab-ai-model.exe verify-bracs-wsi-bags `
  --inventory "D:\PathLabData\BRACS\prepared\bracs-wsi-cohort-15x5-v1\inventory.jsonl" `
  --bags-root "D:\PathLabData\BRACS\derived\kaiko-wsi-cohort-15x5-v1\bags"
```

After a resource-bounded pilot, freeze the complete patient-safe development
inventory and deterministic byte-balanced extraction shards with:

```powershell
pathlab-ai-model build-bracs-development-inventory `
  --full-inventory D:\PathLabData\BRACS\prepared\bracs-wsi-remote-v1\inventory.jsonl `
  --output D:\PathLabData\BRACS\prepared\bracs-wsi-full-development-v1\inventory.jsonl

pathlab-ai-model shard-bracs-wsi-inventory `
  --inventory D:\PathLabData\BRACS\prepared\bracs-wsi-full-development-v1\inventory.jsonl `
  --output-directory D:\PathLabData\BRACS\prepared\bracs-wsi-full-development-v1 `
  --shards 3
```

These commands never include test rows in the development inventory. The complete
split contains 392 train and 68 validation WSIs; test remains locked behind the
unchanged advancement gates.

```powershell
build\ai-training-venv\Scripts\pathlab-ai-model.exe train-bracs-mil `
  --bags-root "D:\PathLabData\BRACS\derived\kaiko-wsi-cohort-15x5-v1\bags" `
  --output-root "D:\PathLabData\BRACS\models\pathlab-bracs-kaiko-wsi-mil-v1" `
  --validation-only --max-epochs 200 --patience 25
```

After validation-only selection, run a new unannotated WSI end to end as a
functional check. This is allowed even when advancement gates fail because it
does not expose the locked test split or establish a performance claim:

```powershell
build\ai-training-venv\Scripts\pathlab-ai-model.exe predict-slide-mil `
  --model-root "D:\PathLabData\BRACS\models\pathlab-bracs-kaiko-wsi-mil-v1" `
  --slide "C:\path\to\unannotated.svs" `
  --output "C:\path\to\mil-evidence.json" `
  --target-mpp 0.5 --max-tiles 128
```

The output includes calibrated probabilities, a review flag, and ranked spatial
tile evidence. Kaiko weights are non-commercial research-only; see the fixed
protocol in `docs/research/BRACS_WSI_MIL_PROTOCOL.md`.

Verify exact feature repeatability on the same real unannotated slide twice:

```powershell
build\ai-training-venv\Scripts\pathlab-ai-model.exe `
  verify-slide-feature-repeatability `
  --slide "D:\PathLabData\BRACS\fixtures\unannotated\BRACS_1003718.svs" `
  --output "D:\PathLabData\BRACS\models\pathlab-bracs-kaiko-wsi-validation-grid-v1\real_slide_repeatability.json" `
  --target-mpp 0.5 --max-tiles 16 --batch-size 8
```

The command fails unless tile coordinates, tissue scores, and encoded features
are exactly identical. Its JSON includes source, feature, loader-code, and encoder
weight SHA-256 values.

The locked test is not evaluated by rerunning training. Once validation gates
pass and test feature bags have been added to a patient-safe combined manifest,
evaluate the frozen selected artifact exactly once:

```powershell
build\ai-training-venv\Scripts\pathlab-ai-model.exe evaluate-bracs-mil-test `
  --bags-root "D:\PathLabData\BRACS\derived\kaiko-wsi-final-v1\bags" `
  --model-root "D:\PathLabData\BRACS\models\pathlab-bracs-kaiko-wsi-validation-grid-v1\SELECTED" `
  --bootstrap-iterations 500
```

The command refuses to run when `final_test_evaluation.json` already exists.
This prevents an accidental second test-set pass. `SELECTED` is the candidate
named in `validation_grid.json`; do not choose it from test performance. Production
evaluation requires the v2 selection record and verifies the validation-grid,
selected-candidate, advancement-gate, model, configuration, evaluation, and
baseline checksum chain before it creates the durable one-time test-attempt lock.
