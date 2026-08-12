# PathLab Forge feature status

The base application remains usable without a catalog or network connection.
Feature Center is the only code path that reads the configured catalog.

| Capability | Delivery | Current status |
|---|---|---|
| WSI inspect, direct preview, annotations, measurements, conversion and upload | Base | Available |
| Pathology hierarchy, H&E ROI analysis, stain tools and TMA primitives | Pathology Tools pack | Implementation boundary complete; not published until a signed pack/catalog is supplied |
| Tissue, nucleus candidates, labelled-pixel classification primitives, QC and affine registration | Classical Analysis pack | Implementation boundary complete; not published until a signed pack/catalog is supplied |
| Pretrained AI inference | Optional AI pack | Unavailable; no model has passed license, provenance, resource and real-WSI gates |
| Training Lab | Optional advanced pack | Unavailable until an approved pretrained model and user-labelled dataset exist |

Forge does not bundle Python, model weights, training data, OpenCV, HistoQC,
PyTorch, ONNX Runtime, CUDA or QuPath. Installed packs are stored outside the
versioned application runtime and may be disabled or uninstalled without
touching datasets or analysis results.

Pack publication requires a PathLab Ed25519 signing key provided at release
time. Private keys must never be committed. Forge accepts only HTTPS catalog
and pack URLs, verifies the signed catalog and pack identity, checks declared
sizes and SHA-256, extracts through a bounded staging directory, runs the pack
self-test, and activates by atomic rename.

AI results, if a pack is approved later, are research/education-only suggestions
and require manual review. Forge does not train or download anything at startup.
