# HistoQC isolated adapter

The default image is pinned to the reviewed upstream digest `sha256:399f09ba85ffd0af9ddf2b2c2080ebf603d381479c6a48ff1638db6ee86e3f6a` (upstream revision `b79561292e49d153e2e4c1401c9c48d3d5f925e1`). An operator must set the two private local mount paths. The container has no network, no Linux capabilities, a read-only root filesystem, one HistoQC process, at most three CPUs, and a 3 GiB memory limit.

Forge must ingest only the resulting QC manifest and review overlays. It does not expose the HistoQC service or input directory over the network. Synthetic fixtures may test blur, folds, pen-like marks, blank tissue, bubbles, colour shifts, compression, and corruption, but they never support diagnostic performance claims.

The included synthetic profile supplies an explicit 20x base magnification because generic TIFFs do not expose an OpenSlide objective-power property. It is a software-QC fixture profile, not a clinical or scanner validation profile. Real-slide activation must use a separately reviewed configuration suitable for the scanner and stain.

Do not replace the digest with `master` or `latest`. The physical 8 GB reference-device run remains an explicit activation gate.

## Synthetic smoke check

Generate the deterministic fixture suite with `pathlab_ai_data.synthetic_pathology.generate_suite`, then set `PATHLAB_HISTOQC_INPUT` and `PATHLAB_HISTOQC_OUTPUT` to private absolute directories and run:

```powershell
docker compose -f ai-runtime/histoqc/compose.yaml up --abort-on-container-exit --exit-code-from histoqc
```

Acceptance requires one `results.tsv` row for each of the seven valid fixtures, review overlays for those rows, and an explicit OpenSlide failure for `synthetic-corrupt-pyramid.ome.tif` in `error.log`. A zero container exit code alone is not acceptance.
