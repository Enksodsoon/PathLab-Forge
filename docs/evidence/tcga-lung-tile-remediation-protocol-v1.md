# TCGA lung tile-selection remediation protocol v1

This is the one allowed remediation after the frozen v1 lung retrieval result.
It is label-blind: project, split, phenotype, retrieval score, and model output
must not participate in tile selection.

For each unchanged checksum-bound WSI:

1. Produce a deterministic 2048-pixel overview.
2. Score overview grid points using tissue color and luminance only.
3. Retain at most nine spatially separated candidates.
4. Extract a 512 x 512 level-zero tile at every candidate coordinate.
5. Score each tile using tissue fraction, color separation, luminance range,
   and adjacent-pixel gradient energy.
6. Select the highest QC score, breaking ties by `y` and then `x`.

The patient, source, reference/query, license, model, normalization, and
retrieval protocols remain unchanged. Recall@5 improvement must remain at least
0.05 and NDCG@10 improvement must remain at least 0.03. Exact ranking
repeatability remains mandatory. OOD and full cross-tissue gates remain
independent and cannot be satisfied by this remediation.

No further lung tile-selection remediation is authorized after this frozen run.
