# Active Task — Modular Capability Release

Supersedes the completed direct-OME release-candidate brief by explicit
product-owner direction on 2026-08-11.

## Fixed endpoint

Deliver a lightweight Forge base plus a signed, offline-by-default Feature
Center. The base owns WSI inspection, direct preview, annotations, geometry
measurements, conversion, validation and upload. Optional Pathology Tools and
Classical Analysis packs are downloaded only after an explicit user action.

AI is not a core feature. Forge bundles no weights or training runtime. At most
one separately downloaded pretrained research model may be published after
license, provenance, resource and deterministic self-test gates pass. Training
Lab is a separate advanced pack using user-provided labelled data.

## Milestones

1. Remove redundant preview work and finish adaptive runtime limits.
2. Add signed catalog, staged installation, self-test, rollback and uninstall.
3. Add bounded raw-region, PathObject, measurement and analysis-run contracts.
4. Add the Pathology Tools pack boundary: hierarchy, H&E, stains and TMA.
5. Add the Classical Analysis pack boundary: tissue/cells/classifier/QC/registration.
6. Add the optional pretrained-AI and Training Lab availability gates.
7. Add explicit private Viewer synchronization with conflict-safe verification.

## Stop rule

Stop after these seven milestones and their local validation report. Do not add
third-party plugins, arbitrary scripting, a dependency solver, cloud services,
microservices, distributed workers, additional models, merge or deployment.

The exact `ome-dynamic-v1` direct path, prepared-v2 fallback, image-quality
gates, artifact reuse, privacy and resumable upload remain regression contracts.
