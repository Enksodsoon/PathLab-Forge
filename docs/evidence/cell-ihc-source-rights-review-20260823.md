# Cell and IHC source-rights review — 2026-08-23

Status: bounded preparation approved; scientific qualification not started.

## MoNuSAC 2020 cell annotations

The official challenge data page declares CC BY-NC-SA 4.0 and describes expert
nuclear-boundary annotations across lung, prostate, kidney, and breast. This is
eligible only for the restricted private-research lineage. It is not eligible
for Atlas-Clean, commercial deployment, or any clinical claim. The exact
official Google Drive IDs and response sizes are now pinned for the training
archive (545,564,883 bytes), test archive (202,746,703 bytes), and supplementary
organ metadata (19,590,377 bytes). The organizer does not publish cryptographic
checksums for these files. The acquisition therefore freezes local SHA-256
values after exact-size transfer and remains `not_evaluable` until archive
metadata is extracted and upstream integrity is independently corroborated; no
mirror or inferred weight license is accepted.

`acquire-monusac2020.ps1` performs that bounded acquisition in the interactive
user context using 8 MiB range checkpoints and publishes only restricted-use
source-ledger records. It does not submit a model or qualification campaign.

Source: https://monusac-2020.grand-challenge.org/Data/

## TumorQuantAI breast IHC patches

Zenodo record `21797920`, DOI `10.5281/zenodo.21797920`, declares the dataset CC
BY 4.0. The record contains separate H&E, ER, PR, HER2, and Ki-67 fields from 51
pseudonymized cases with per-file integrity metadata. It explicitly states that
sections/fields are not registered, provides no pathologist-verified invasive
tumor ROI, laboratory controls, diagnostic truth, or independent clinical
validation, and describes computational measurements as research proxies.

Therefore this source may support bounded descriptive algorithm/QC fixtures,
but cannot qualify clinical categories, cross-slide cell correspondence, PD-L1,
diagnosis, prognosis, or treatment claims. Dataset, software, and gated model
rights remain separate. `prepare-tumorquantai-ihc-source.ps1` downloads only the
pinned metadata bundle (at most 2 MiB); case archives remain blocked until a
case-disjoint subset and non-clinical reference protocol are frozen within the
source quota.

Source: https://zenodo.org/records/21797920
