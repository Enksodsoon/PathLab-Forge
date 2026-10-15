# Integrated local checks, 2026-10-08

Baseline Forge main7de0d915 and reader32a4a36; Viewer mainfac64762. No deployed Viewer SHA verified. Historical reader artifacts are NON_REDISTRIBUTABLE.

GitHub main Windows failure: run31576911883, ForgeLibraryApiTest.inspectsSelectsAndConvertsVsiInBackground line384, expected READY_TO_CONVERT after asynchronous verification. Reproduced deterministically on integration with ConversionVerificationRaceTest.verificationCompletingInsideInspectionCannotBeOverwrittenByTheOldRecord: verification finishes inside metadata inspection; old dataset save overwrites verified fingerprint/status. New test failed at fingerprint assertion. Fixed shared updateInspectedDataset with atomic repository.update using current identity and effective verified state. Both race tests and original library journey now pass. Full checks rerun after integration changes.

Queued cancellation also reproduced: a paused queued dataset had no artifact revision; cancellation attempted to resolve an empty artifact ID. Shared cleanup guard now preserves valid artifacts and skips absent revisions. Pause persistence and paused queued cancellation tests pass.

Native privileges: clean desktop /app cannot mint a session; renderer cannot authorize guessed source paths or quit lifecycle. Only private main-process secret authorizes native dialog grants/lifecycle. Loopback browser/headless compatibility retained. Desktop policy tests and syntax check pass. Final packaged artifact smoke must rerun after current integration.

Export failure check: an altered artifact hash leaves existing completed destination intact; correct copy is verified and atomically replaces destination; source cannot be its own destination. One bounded active export, cancellation checked during copy/verification and locked finalization, no non-atomic fallback.

Unverified: actual Mac Keychain/signing/process-tree telemetry, all four target-platform acceptance matrices, production owner isolation, authorized runtime rights, signed installers, authenticated production downloads, upgrade/restore acceptance. Unit tests and an unsigned internal installer do not close these gates.
