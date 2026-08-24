# MoNuSAC cell-instance qualification result — 2026-08-24

Status: `experimental`; frozen target not met.

The official restricted MoNuSAC acquisition completed all 767,901,963 bytes.
The held-out cohort contains one annotation-bearing field from each of 23 test
patients across lung, kidney, breast, and prostate. `TCGA-MP-A4T7` and
`TCGA-A2-A0ES` were excluded because they also occur in the training archive.
The immutable cohort manifest SHA-256 is
`63885be5c1e271669acf9debaba2419b0b8cddeb6a7e5bf102d751e55a9727c8`.

Campaign `monusac-od-watershed-heldout-20260824-v1` completed with
`campaignCompleted=true` and `campaignTargetMet=false`. Its manifest SHA-256 is
`cd04a5866a55614d46cac280657e753c46b218ad9829cd02139eaeaf9e6dbc0c` and
signed attestation SHA-256 is
`deced88301b084c055bf44676d1f51cc64f922efb209e6adf363a07d184dc724`.

The unchanged held-out results were:

- macro PQ `0.0010429883` versus minimum `0.45`;
- instance Dice `0.0008482910` versus minimum `0.70`;
- mean count error `4.9451488886` versus maximum `0.15`;
- morphometry bias `1.0` versus maximum `0.10`;
- failed-region rate `0.0` versus maximum `0.05`;
- deterministic repeat and four-tissue coverage passed.

The first evaluator also exceeded the pack's resource envelope, reached about
5.1 GB process working set, took more than 120 seconds, and temporarily blocked
dashboard status responses. Its signed report correctly records resource
failure, so it cannot qualify or activate the pack.

The bounded-memory correction replaces per-instance full-frame bitsets and
pairwise bitset cloning with compact sorted pixel arrays, bounding-box pruning,
and allocation-free two-pointer intersection. A local run against the exact
same cohort and gates completed in `7.320365099` seconds with `395.943359375`
MiB peak heap and reproduced every scientific metric exactly. Runner `2.1.6`
passed the complete build, but its administrator installation was declined at
the UAC prompt. Therefore no signed remediation campaign has run yet and the
installed service remains `2.1.5`.

This is a useful negative baseline: deterministic OD watershed is not adequate
as the primary all-rounder cell detector on this cohort. It remains an
automatic fail-closed fallback. The next model candidate is HoVer-Net fast mode,
subject to separate code/weight rights and resource qualification.
