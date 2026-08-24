# HoVer-Net fast MoNuSAC — research candidate

## Intended scope

Offline, local, descriptive nucleus-instance detection for private pathology
research and engineering evaluation. Predicted type indices are retained only
as research model categories and are not definitive tumor, lymphocyte, stromal,
or diagnostic identities.

## Lineage

- Code: official HoVer-Net revision
  `67e2ce5e3f1a64a2ece77ad1c24233653a9e0901`, MIT.
- Weights: official fast MoNuSAC checkpoint, locally frozen SHA-256
  `5b1c642d9884e20c8fa0b80a6cfef793f483d47eaa5df6183baddc3f57e88a35`,
  tracked as `CC-BY-NC-SA-4.0` restricted research lineage.
- Post-processing overlay: SciPy 1.18.1 wheel SHA-256
  `5e4d44984abc0020154ea81b247adeddcc3ac5527b975ff798bd1ba0adc513c2`,
  BSD-3-Clause.
- Runtime: shared checksum-pinned PyTorch 2.7.1+cu126 runtime with `sm_61`.

## Current evidence

Two synthetic runtime probes on the Quadro P2000 produced identical instance
and research-category counts within 532 MiB VRAM and approximately 1.1 GiB RAM.
This is runtime conformance only.

## Status and limitations

`not_evaluable`, inactive, research-restricted, and not Atlas-Clean eligible.
No held-out MoNuSAC accuracy metrics have run through this worker. No diagnosis,
clinical cell identity, prognosis, treatment guidance, or clinical scoring is
supported.
