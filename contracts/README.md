# Forge / Viewer release contracts

The approved non-AI release brief is `docs/plans/active/current.md`. Normal delivery is negotiated direct OME (`ome-dynamic-v1`), with structured private results transferred separately. Explicit Teaching delivery uses the existing prepared static-DZI ingest contract. These artifact identities must remain separate.

Reuse the existing Viewer acceptance implementations and exact fixtures. Do not privately invent a replacement schema or infer support from a filename. Changes require matching producer/consumer checks and a recorded Viewer commit.

Current Forge contract checks and fixtures:

- `src/test/java/org/pathlab/forge/viewer/ViewerCapabilitiesTest.java`: negotiated formats, profiles, limits and ingest-creation idempotency.
- `src/test/resources/viewer-sync-v1`: snapshot, change, mutation, conflict and retained-content fixtures.
- `src/test/resources/study/viewer-v1.json` and `viewer-v1.checksum`: `pathlab.study-pack/1` canonical content and checksum.
- `src/test/resources/study/viewer-authoritative-v1.json`: byte-identical to the committed Viewer `tests/fixtures/study/viewer-v1.json` at scoped compatibility commit `a55cf3a0`, SHA-256 `c39c96744302120d336239ad839a506028e6df85513fba699f570d3d3c1e722f`. Explicit LF checkout preserves those source bytes on Windows. Java validates its exact canonical checksum alongside the additional manual/spatial fixture.
- `src/test/resources/study/canonical-numbers.json` and `canonical-numbers.txt`: cross-language numeric canonicalization.
- `PrivateResultsBundleBuilderTest`: immutable original run/ROI/review identity, geometry transforms and exact result hashes.

Viewer original default-branch baseline was `fac6476249ed287bb2afe8def1b0bcc9558104d7`; fresh main `81fcc80c5f2de81a7ff47f526aaf8f24742637fb` is integrated into the scoped compatibility branch. Its stable verification source/receipt commit `27c081886eac3d00a06b718fe57ec1aea93c4b4a` passed 1,481 backend tests (111 skips), 578 frontend tests, six browser contract journeys and 492 load checks (12 skips). Final evidence-only head is `57b12a1df5b3ac6889606198da757292799af05f`. The authoritative fixture remains unchanged from `a55cf3a0`. PostgreSQL, protected CI, deployment and authenticated production journeys remain open; the deployed commit is unverified. Publication, private libraries and release admission cannot be declared qualified from these local checks alone.

The earlier derivative-only package description is historical. It does not change the normal OME release default. Breaking wire changes use a new version; never silently redefine an existing version.
