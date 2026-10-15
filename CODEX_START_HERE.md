# Start here: PathLab Forge desktop release

Read `docs/plans/active/current.md` and `docs/plans/active/RELEASE_PROGRESS.md` first. The approved work is the complete non-AI Windows/macOS release, not the obsolete F1.1 foundation task.

Use Java 17, React, SQLite and the existing streaming pipeline. Electron owns the desktop window and service lifecycle. Normal delivery retains verified OME; Teaching delivery explicitly produces independently identified static DZI. Viewer remains authoritative for identity, permissions, privacy, publication and learner records.

Build in isolated `codex/` worktrees, with one integration owner and at most three active workers. Integrate focused commits and run checks on the combined head. Preserve user data and historical evidence. Never treat unverified runtime rights, signing, actual-platform testing, deployment or production verification as complete.

Development: `gradlew test` runs Java tests and the production interface build. Run frontend tests separately with `pnpm test` in `frontend`, desktop checks with `pnpm test` and `pnpm check` in `desktop`, and repository policy with `scripts/verify-repo.ps1`. Use JDK 17 explicitly on machines whose default Java differs.

`gradlew run --args=--serve` retains the browser/headless development mode. The packaged Electron app uses `--desktop`, bundled resources, private readiness and one-time local authorization. Development runtime discovery does not qualify a clean-machine release.

Record acceptance receipts and remaining blockers in the progress register. No paid service, certificate purchase or cloud spend is automatic.
