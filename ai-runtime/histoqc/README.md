# HistoQC isolated adapter

HistoQC is disabled until an operator records an approved immutable container digest and sets the two private local mount paths. The container has no network, no Linux capabilities, a read-only root filesystem, at most three CPUs, and a 3 GiB memory limit.

Forge must ingest only the resulting QC manifest and review overlays. It does not expose the HistoQC service or input directory over the network. Synthetic fixtures may test blur, folds, pen-like marks, blank tissue, bubbles, colour shifts, compression, and corruption, but they never support diagnostic performance claims.

Do not replace the digest with `master` or `latest`. Image pull, licence review, checksum registration, and the physical 8 GB reference-device run are explicit activation gates.
