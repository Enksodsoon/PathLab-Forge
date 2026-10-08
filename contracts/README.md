# Forge / Viewer release contracts

The approved non-AI release brief is `docs/plans/active/current.md`. Normal delivery is negotiated direct OME (`ome-dynamic-v1`), with structured private results transferred separately. Explicit Teaching delivery uses the existing prepared static-DZI ingest contract. These artifact identities must remain separate.

Reuse the existing Viewer acceptance implementations and exact fixtures. Do not privately invent a replacement schema or infer support from a filename. Changes require matching producer/consumer checks and a recorded Viewer commit.

Current Forge contract checks and fixtures:

- `src/test/java/org/pathlab/forge/viewer/ViewerCapabilitiesTest.java`: negotiated formats, profiles, limits and ingest-creation idempotency.
- `src/test/resources/viewer-sync-v1`: snapshot, change, mutation, conflict and retained-content fixtures.
- `src/test/resources/study/viewer-v1.json` and `viewer-v1.checksum`: `pathlab.study-pack/1` canonical content and checksum.
- `src/test/resources/study/viewer-authoritative-v1.json`: byte-identical to Viewer `tests/fixtures/study/viewer-v1.json` at scoped compatibility commit `a55cf3a0`, SHA-256 `94a592c1d71abeb4bd48a76cc178a124f564c00d2c9cf85cc049db59d72832fb`. Java validates its exact canonical checksum alongside the additional manual/spatial fixture.
- `src/test/resources/study/canonical-numbers.json` and `canonical-numbers.txt`: cross-language numeric canonicalization.
- `PrivateResultsBundleBuilderTest`: immutable original run/ROI/review identity, geometry transforms and exact result hashes.

Viewer default-branch baseline is `fac6476249ed287bb2afe8def1b0bcc9558104d7`; locally qualified scoped compatibility candidate is `a55cf3a0`. Its deployed commit remains unverified. New publication and private-download routes stay gated until matching scoped Viewer contracts and actual authenticated journeys pass.

The earlier derivative-only package description is historical. It does not change the normal OME release default. Breaking wire changes use a new version; never silently redefine an existing version.
