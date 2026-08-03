# Optional Research AI Lab runtimes

Every runtime in this directory is fail-closed and research-only. Forge reports an adapter as ready only when its approved local executable and required checksum-addressed artifacts are configured. No runtime or model weight is bundled into Git.

- HistoQC uses the isolated container contract in `histoqc/`.
- WSInfer is admitted only through `PATHLAB_WSINFER_CLI`; the wrapper must use the breast research model, no more than three loader workers, bounded sampling, and at most five evidence regions.
- GigaPath-Flash/Kaiko use `PATHLAB_FOUNDATION_CLI` plus `PATHLAB_FOUNDATION_MODEL_ROOT`; full Prov-GigaPath and CONCH remain disabled.
- MONAI Label uses `PATHLAB_MONAI_LABEL_CLI` as a loopback-only optional sidecar. Its output remains a temporary Viewer candidate layer.
- The local LLM uses `PATHLAB_LLAMACPP_CLI` and `PATHLAB_LLM_MODEL`; the wrapper must cap context at 4096, output at 512, and return insufficient approved evidence when retrieval has no approved source.
