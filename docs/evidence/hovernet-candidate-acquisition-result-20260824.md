# HoVer-Net candidate acquisition result — 2026-08-24

## Result

The official HoVer-Net code and the official fast-mode MoNuSAC checkpoint are
now acquired and locally checksum-frozen as the restricted research candidate
`hovernet-fast-monusac-v1/1`.

- Upstream repository: `https://github.com/vqdang/hover_net.git`
- Exact code revision: `67e2ce5e3f1a64a2ece77ad1c24233653a9e0901`
- Code archive SHA-256: `a14857014569fc169371f04832fe96e605f755d35151f59901e1ee7176a05914`
- Weight file: `hovernet_fast_monusac_type_tf2pytorch.tar`
- Weight bytes: `150995854`
- Weight SHA-256: `5b1c642d9884e20c8fa0b80a6cfef793f483d47eaa5df6183baddc3f57e88a35`
- Shared runtime manifest SHA-256: `b52c4d80f914c7e6e56d05e594d9478333d763861b5133e3c82af2a6a17fd942`

The upstream publisher does not provide a cryptographic checksum for this
Google Drive weight. The SHA-256 above is therefore a local first-acquisition
freeze, not independent publisher corroboration.

## Rights boundary

HoVer-Net code is MIT licensed. The checkpoint is tracked separately under the
MoNuSAC-derived `CC-BY-NC-SA-4.0` restricted research lineage. It is eligible
only for the research lineage, is not Atlas-Clean eligible, is not
redistributable by this project, and cannot activate itself.

## Runtime audit

The acquired checkpoint loads successfully with `torch.load(...,
weights_only=True)` in the existing checksum-pinned Python 3.12 / PyTorch
2.7.1+cu126 runtime. CUDA 12.6 is available on the P2000 and the checkpoint
contains 668 parameter entries.

The original inference and post-processing stack is not yet executable in that
runtime. `opencv`, `scipy`, `scikit-image`, `docopt`, and `torchvision` are not
installed. A separate checksum-pinned compatibility overlay or a reviewed
minimal post-processing adapter must be built and tested without mutating the
already-qualified DINOv2 runtime.

## Qualification status

`not_evaluable`: acquisition and safe checkpoint loading are complete, but
offline tiled inference, instance post-processing, progress/checkpoint support,
resource compliance, and the frozen 23-patient held-out evaluation have not run.
No learner evidence, activation, clinical cell identity, or Atlas teacher claim
is permitted from this acquisition result.
