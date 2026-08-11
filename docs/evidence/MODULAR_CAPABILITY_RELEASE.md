# Modular capability release evidence

Date: 2026-08-11

Branch: `codex/modular-capability-release`

Forge base: `5ece3f9953df2efcd7c77928cfc9e1f10f2e1309`

Viewer base: `b557baf8518250b1898bed073b8af3fa8e6e4bd9`

## Demonstrated locally

- Base remains offline at startup. Feature catalog access occurs only after an
  explicit Feature Center refresh.
- Signed-pack flow enforces HTTPS, Ed25519 signatures, SHA-256, declared sizes,
  safe archive paths, bounded extraction, bounded self-test, staged activation,
  restart discovery, disable and uninstall.
- Expanded `installDist`: 19,480,896 bytes; base: 19,418,363; delta: 62,533 bytes.
- Compressed `distZip`: 19,175,478 bytes; base: 19,116,174; delta: 59,304 bytes.
- Main application JavaScript: 66,399 bytes; base: 63,551; delta: 2,848 bytes.
- Synthetic tests cover geometry, H&E, stain tools, TMA, tissue/QC, nucleus
  candidates, labelled-pixel classification and affine registration.
- Real-file acceptance read a bounded 256 by 256 region from
  `Slide no.7-CS22-123.ome.tif` (347,802,241 bytes) through Bio-Formats and
  produced finite hematoxylin and eosin measurements.
- Viewer synchronization is explicit, private-only and refuses to overwrite a
  remote annotation set that is not empty. Viewer repository code is unchanged.

## Deliberately not claimed

- Pathology Tools and Classical Analysis are not published downloads. Release
  publication needs a PathLab catalog URL, Ed25519 public key, signed archives,
  licenses and pack-level real-WSI acceptance evidence.
- No pretrained model passed the required gates; no AI or Training Lab pack is
  published or bundled.
- Cold-start and idle-memory deltas have not been measured with a repeatable
  cold-cache harness. Their numeric gates remain release-acceptance work.
- HistoQC and QuPath adapters are not bundled. Feature Center may expose them
  only through a future signed Classical Analysis pack with its exact runtime.
- No production upload, deployment, catalog publication, signing-key operation,
  merge or release was performed.

## Stop boundary

No third-party SDK, dependency solver, scripting language, marketplace backend,
model zoo, cloud training, microservice or distributed worker was added. Further
features require a new approved plan.
