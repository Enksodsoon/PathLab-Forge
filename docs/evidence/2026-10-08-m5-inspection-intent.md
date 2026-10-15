# M5 inspection intent and recovery - 2026-10-08

Worker base: `2d9bacf`; Windows x64, Java 17. These are engineering fixtures, not real OME/SVS/VSI or installer qualification.

Batch creation persists every original source/settings record before metadata inspection. `BatchRun.Item.initialSnapshot` preserves that original intent; `snapshot` becomes the exact prepared configuration. Existing five-field JSON records default `initialSnapshot` to the old saved snapshot, without changing old settings.

Unselected/unverified items run through the existing single conversion executor and maintenance scheduler. No extra pool is created. Queue pause prevents a new inspection; active conversions and metadata preparations share the same worker. Metadata inspection and streaming source hashing run outside the BatchService, ConversionService and dataset repository monitors. Full source identity is checked against the saved inventory before preparation; an upgraded background digest is accepted only for identical source metadata and matching content. The shared interactive series-selection logic supplies the deterministic initial RGB choice. A concurrent user source/series/crop/view change fails the item instead of being overwritten.

The complete prepared snapshot is persisted atomically as PREPARED before the short dataset CAS or queue admission. Recovery can complete this checkpoint if the dataset CAS was not applied, or recognize an already-applied configuration. Existing expected admission still pins artifact/checkpoint identity before exposing the queue row. Batch cancellation is durable before interrupting an active inspection; late reader completion cannot admit a cancelled item. Preparation captures the saved attempt at scheduling and checks attempts, initial intent and snapshot identity at entry, success and failure, so an old cancelled reader cannot overwrite a newer retry even if it ignores interruption. Streaming digest cancellation checks between 1 MiB reads. BatchService.close unregisters its scheduler callback and interrupts its owned preparation.

Root wiring:

1. Remove the frontend per-slide `inspectDataset` loop before `createBatch`; send selected known IDs directly to `createBatch`.
2. Retain deferred ConversionService construction, `batchService.recoverPending()`, then `conversionService.resumeQueueDispatch()`.
3. Close BatchService before ConversionService and the dataset repository. No new API route is needed; creation returns a durable pending batch before reader completion.

Focused fixtures cover blocked external inspection without blocked status/cancel, durable pause, concurrent user selection/CAS refusal, PREPARED-before-dataset-commit recovery, legacy JSON compatibility, source digest cancellation, cached-series source revalidation, delayed cancelled-reader failure/success after a retry, and a fresh ten-item batch with seven successes and three deliberate failures. Only the failed render is retried; successful artifacts retain their exact identity. Failures are labelled synthetic metadata/render errors and an unsupported synthetic channel plane.

A forked Java fixture was actually terminated with Process.destroyForcibly() while its synthetic metadata reader was INSPECTING after the durable manifest commit. Reopening unchanged inputs completes the saved intent. Separate killed-process cases refuse modified input, missing input and an added VSI companion before a reader call. This exercises SQLite/WAL plus process interruption, but does not qualify Bio-Formats/QuPath/libvips or real native-reader process containment.

Open qualification issue: one first combined forced-kill test run failed reopening the source SQLite library with SQLITE_IOERR_TRUNCATE at SqliteDatasetRepository.configure (`PRAGMA journal_mode=WAL`). Source/database files were preserved. The exact mutation test and the subsequent combined focused run passed without retries, sleeps, deletion or a database workaround. The intermittent truncate failure remains an explicit Windows database recovery qualification gap.

`restoreSeries` now verifies the selected source before returning its in-memory cache, as requested by integration. This prevents stale geometry from bypassing source checks in brush/series callers.

Final worker checks (base 2d9bacf plus this change): full Java `./gradlew.bat test --console=plain` passes 323 tests, 9 explicit skips, 0 failures/errors across 94 suites. The new delayed-unwind regression passes for late failure and swallowed-interrupt metadata success; successful source-keyed cached metadata can be reused without committing the obsolete attempt. The existing batch HTTP journey now polls the asynchronous durable outcome with a five-second bound. Full frontend `pnpm.cmd test` passes 87 tests in 15 files; production TypeScript/Vite/bundle build, repository policy and `git diff --check` pass. These counts precede root integration and do not claim installer or real-slide qualification.
