# M5 durable batches - 2026-10-08

Engineering fixtures on Windows x64, Java 17, React 19; this document does not qualify real OME/SVS/VSI readers or an installer.

`BatchStore` persists bounded batches (1-1000 unique dataset IDs, list pages of at most 100) in SQLite with WAL and synchronous FULL writes. The manifest contains each exact original `LocalDataset` before any conversion dispatch. `ConversionService.startExpected` checks source identity and all conversion settings, reserves the artifact/checkpoint, persists admission through the callback, and only then exposes the durable queue row. Failures in the manifest callback cannot dispatch work. Pending admissions and admitted records missing their queue row reuse their existing revision on recovery.

Startup order is required: construct `ConversionService(repository, engine, derivative, managedRoot, true)`, construct `BatchService`, call `recoverPending()`, then `resumeQueueDispatch()`. This keeps persisted batch-wide cancellation requests ahead of every reader. It preserves the independent persisted user pause state. Root integration owns these calls and HTTP/App wiring.

`BatchServiceTest` uses tiny synthetic TIFF-signature bytes, a synthetic reader/derivative, forced render/transfer/manifest failures, and persisted checkpoint interruption fixtures. Coverage:

- Ten mixed items: seven successful synthetic local outputs, one forced render failure, two unconfigured source failures; retry exactly the failed render item, with nine total reader calls.
- SQLite snapshots and outcomes reopen; reports retain original settings and pinned artifact IDs after current dataset settings change or the dataset is removed.
- Failed scoped transfer reporting does not invoke conversion.
- Paused queue and pinned identity survive reopen; an interrupted manifest admission missing its queue row recovers once without duplicate entries.
- Cancellation and whole-batch cancellation intent survive reopen, including before scheduler activation; sources remain present and completed outputs remain present.
- SOURCE_VERIFIED, REGIONS_RENDERING, OME_VERIFIED and PACKAGE_COMMITTED restart fixtures retain identity. Verified OME is revalidated without invoking the reader again.
- Changed source during pause fails before rendering. Changed settings before admission fail closed. Manifest callback failure produces no queue row.
- CSV quotes/newlines and spreadsheet formula prefixes are escaped.
- Only explicit `startTeaching(id)` persists PREPARED_DZI_V2. Ordinary `start(id, format)` retains direct-OME coercion. Batch creation presently accepts direct OME only.

`BatchReports.test.tsx` checks saved identities, exactly scoped per-item retry, original batch export and disabled cancellation for finished items. Production TypeScript build and existing bundle budget pass; full frontend suite: 80 tests passed (11 files). Existing App tests emit act warnings.

Required release evidence remains open: real ten-slide OME/SVS/VSI batch, native process kill at inspection/rendering/validation/finalization, disk-full on actual managed storage, actual desktop UI/keyboard/scaling/assistive technology, packaged Windows/macOS artifacts, and authenticated scoped Viewer delivery. Checkpoint fixtures are not a native-process kill result. Teaching queue selection alone does not qualify actual package generation.

Verification: `./gradlew.bat test` passed at the final worker tree; 7 focused M5 tests passed. `pnpm.cmd test` passed (80 frontend tests); `./gradlew.bat frontendBuild`, `scripts/verify-repo.ps1`, and `git diff --check` passed. Root must rerun integrated exact-head checks after wiring.
