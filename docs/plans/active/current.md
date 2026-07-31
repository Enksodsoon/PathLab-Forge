# Active Task — Project Batch Queue and Persistent Crop Workspace

Supersedes the completed conversion-speed tuning wave by explicit product-owner approval.

## Goal

Make Forge practical for project-scale conversion:

- import multiple slides or recursively scan one project folder;
- group VSI sources with their ETS companions and isolate incomplete datasets;
- persist a batch queue across restart;
- run more than one conversion only when CPU, RAM, disk and source-volume capacity permit;
- queue automatically under load instead of rejecting conversion requests;
- retain each slide's draft and saved crop rectangle while the crop controls are closed,
  while switching slides, and after application restart.

## Scheduling contract

- `Auto` is the default mode.
- The 8 GB / 6-core minimum runs one heavy conversion.
- Higher-spec systems may run up to two conversions in this milestone.
- Admission uses bounded memory and workspace reservations and never launches unlimited workers.
- Jobs that cannot start remain queued with an actionable wait reason.
- A queued job owns an immutable series/crop/downsample configuration snapshot.
- Cancellation, retry and restart affect one job without deleting completed packages.

## Ordered work

1. Persist batch-job records in SQLite and per-slide crop drafts in the local app profile.
2. Add multi-file and recursive folder discovery with complete VSI/ETS grouping.
3. Replace direct conversion rejection with an adaptive persistent scheduler.
4. Add the compact project/queue workflow and bulk actions to the React shell.
5. Keep saved and draft crop overlays visible per slide outside crop-edit mode.
6. Verify minimum-profile serialization, higher-profile concurrency, overload queuing,
   restart recovery, crop persistence and real project-folder discovery.

Viewer deployment, Viewer database changes, publication behavior and automatic public sharing
remain out of scope.

## OME-TIFF viewer repair

The source viewer must never decode a large single-resolution OME region on an HTTP thread.
On first open, Forge builds one disposable, fixed-Q85 local DZI in a short Windows-safe cache
path and exposes explicit preparing/failed state. The completed pyramid is atomically published,
reused across restarts, and served as static tiles. Production package quality selection remains
unchanged and the viewer cache is never approved or uploaded.
The cache identity is the source fingerprint plus selected image series; crop, downsample,
conversion status and artifact-history edits must not rebuild the original-source viewer.
