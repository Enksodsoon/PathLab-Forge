# PathLab AI automatic evidence-region viewer

## Outcome

Forge can now run the trained BRACS Kaiko gated-attention MIL model on an
unannotated local WSI, cluster positive predicted-class tile contributions into
distinct suspected-evidence regions, automatically select the strongest region,
and navigate the whole-slide viewer to it.

This is a research and education workflow. A region is model evidence, not a
confirmed disease boundary, a segmentation mask, or a diagnosis.

## Region-selection method

1. Keep only tiles with a positive additive contribution to the predicted class.
2. Rank them deterministically by contribution and source coordinate.
3. Retain at least three tiles and enough tiles to cover 80% of the positive
   contribution, capped at 16 tiles.
4. Join spatial neighbors within 1.75 source-tile widths.
5. Rank clusters by summed positive contribution and return at most five.
6. Mark the leading cluster as the single automatic selection.

The complete ranked tile evidence remains in the result for reproducibility.
No slide annotations or human-drawn regions are required to run inference.

## Real WSI verification

### Public BRACS fixture

- Slide: `BRACS_1003718.svs`, 17,135 x 11,733 pixels.
- Runtime: 5.70 seconds for 128 representative tissue tiles.
- Prediction: N / BT, confidence 0.330417; review required.
- Automatically selected region: x 8,436, y 2,664, width 888, height 444.
- Distinct suspected-evidence regions returned: 5.

### Local laboratory VSI through its Forge staging OME-TIFF

- Slide: `SP-68-7354-C_U129 HER-2_20250501.vsi`.
- Original source geometry: 113,029 x 74,795 pixels.
- Model input: Forge-managed `export.ome.tif`, 75,352 x 49,863 pixels.
- Runtime: 5.75 seconds for 128 representative tissue tiles.
- Prediction: N / BT, confidence 0.256518; review required.
- Distinct suspected-evidence regions returned: 5.
- Forge records the crop/downsample transform and projects model regions back to
  original-slide coordinates before drawing or navigating.

The VSI test demonstrates the intended real-world adapter boundary: Forge owns
vendor-container conversion while the AI model consumes a validated TIFF WSI.

## Runtime configuration

The local server discovers the separate research runtime through:

- `PATHLAB_FORGE_AI_CLI`, pointing to `pathlab-ai-model.exe`;
- `PATHLAB_FORGE_AI_MODEL_ROOT`, pointing to the selected MIL candidate.

Only one analysis can run at a time. Each process has a 20-minute limit, output
is written atomically under Forge's managed `ai-research` directory, and dataset
identifiers are path-bounded. The UI uses the existing authenticated, CSRF-
protected loopback API.

## Human-in-the-loop boundary

The AI does the initial slide sampling, prediction, evidence clustering,
selection, and navigation without human annotation. A learner or researcher
remains responsible for reviewing the highlighted tissue, comparing alternative
regions, and rejecting unsupported output. The current selected model did not
pass its predeclared advancement gates, so the UI always presents the result as
research evidence and preserves the model's confidence-review requirement.

## Verification

- Python model tests: 19 passed.
- Frontend tests: 37 passed.
- Complete Gradle test suite: passed.
- Production bundle budget: passed (`app.js` 83,911 bytes).
- Real public SVS inference: passed.
- Real laboratory VSI staging-OME inference: passed.
