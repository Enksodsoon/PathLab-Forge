# Data upgrades and recovery

The installed Java application's `Implementation-Version` supplies its semantic version. Under the held `forge.lock`, `ForgeApp` runs `DataUpgradeRecovery.prepare` before opening any SQLite store. The first installed launch on an unversioned root and each strictly newer version create a backup before schema/recovery writes. Same-version restarts reuse the already completed receipt. A receipt records `maximumAppVersion`, `minimumRollbackVersion`, `previousAppVersion`, UTC creation time, exact database paths/bytes/SHA-256 and successful SQLite integrity verification.

The minimum rollback version conservatively equals the maximum version that reached the schema gate. An older binary is refused even if its schema might happen to work. A development/test build with no installed version may use an unversioned disposable root; it cannot open a root with installed-version receipts. Production startup requires an installed version. Reader self-test bypasses the gate and legacy Mac copying, and does not open or migrate data stores; it still acquires the root lock. Never point development server tests at an installed user root.

## Backup scope and guarantees

Only these databases are snapshotted, including every existing connection namespace:

| Location | Contents |
| --- | --- |
| `forge.db` | Library, conversion/batch queue, base Viewer delivery store |
| `forge.db.<64 lowercase hex>.db` | Connection-scoped Viewer delivery jobs |
| `viewer-sync.db` and `viewer-sync.db.<64 lowercase hex>.db` | Library snapshots, edits, conflicts, retained-copy identities |
| `managed/analysis-runs.sqlite` | Deterministic analysis provenance/reviews |
| `managed/study-authoring.sqlite` | Study drafts, revision history and exports |

The already installed sqlite-jdbc native backup API reads a consistent snapshot including committed WAL pages and preserves rowids. Source connections are read-only; no schema is written during backup. Sources and copies pass `PRAGMA integrity_check`; copies are flushed, hashed and verified. The helper uses a 256-page SQLite cache, 128-page backup steps and a 1 MiB hashing buffer. It refuses more than 512 databases, 2 GiB per database or 8 GiB total logical database size, insufficient free space, invalid receipts, symlinks/redirected paths and databases outside the canonical root. Busy retries are bounded. All Forge writers must obey the root lock; an unrelated SQLite editor is not covered by that lock. See the [SQLite backup API](https://www.sqlite.org/backup.html) and [pinned sqlite-jdbc API](https://github.com/xerial/sqlite-jdbc/blob/3.50.3.0/src/main/java/org/sqlite/core/DB.java).

Snapshots are written into `upgrade-backups/snapshot-<UUID>.partial`. Only after all copies and the receipt verify does an atomic directory rename publish `snapshot-<UUID>`. `receipt.json` and `receipt.sha256` are flushed before publishing. Published receipts/backups are append-only from the application's perspective: it never replaces, edits or automatically removes them. Startup reads bounded receipts and their checksums to obtain the version floor; full snapshot hashing/integrity checks run at creation and explicit restore validation, not on every same-version startup. The helper stops at 1,024 history entries instead of deleting backups to free space. Interrupted `.partial` directories remain available for investigation and can be inspected separately; they do not authorize schema writes or rollback.

A failed backup stops startup and leaves originals and any partial copies intact. Native filesystem/power-loss durability still requires actual target-platform acceptance. These metadata backups do not recursively copy WSI, OME, tile trees, offline copies, settings, JSON annotations, Study assets or other managed artifacts. Upgrades leave those existing files in place. They are required alongside the databases for a complete recovery; metadata snapshots are not full data-root backups. Existing legacy Mac migration retains its original root and happens before the new root's schema gate.

## Actionable startup codes

The private desktop startup pipe contains only protocol and one fixed code, never paths, exception text or credentials:

| Code | Action |
| --- | --- |
| `DATA_UPGRADE_BACKUP_FAILED` | Keep Forge closed. Check available space, permissions, regular file paths and SQLite integrity. Retry after correcting the cause; retain partial backups. |
| `DATA_DOWNGRADE_BLOCKED` | Install the recorded version or a newer qualified version. Do not remove receipts to bypass the floor. |
| `DATA_VERSION_REQUIRED` | Use a correctly packaged versioned application, or a separate disposable development root. |
| `DATA_UPGRADE_RECOVERY_REQUIRED` | Backup history is damaged/incomplete. Preserve the complete root, investigate checksums and recover with a verified compatible copy. |

## Explicit operator restore

Restore is an operator action, never an automatic startup fallback. Stop the desktop and service first. Keep the entire current root and its WAL/SHM/journal files; preserve the original receipt history. Confirm that the selected completed backup's `previousAppVersion` identifies the desired pre-upgrade state; `unversioned` conveys no older-release compatibility claim. Compare the complete current database inventory with the receipt. Any newly created database or changed external annotation/artifact requires a deliberate recovery decision, not silent deletion. Restore every database in a selected receipt as one maintenance operation, not one account or draft database in isolation.

Use the packaged Java libraries in an operator Java 17 program, with the root lock held for validation and **all** subsequent file operations:

```java
import java.nio.file.*;
import org.pathlab.forge.runtime.*;

try (var lock = DataRootLock.acquire(dataRoot)) {
    DataUpgradeRecovery.verifyBackup(dataRoot, lock, receiptJson);
    // Keep this lock open through the complete restore described below.
}
```

`verifyBackup` accepts only a receipt directly beneath this root's `upgrade-backups`, checks receipt SHA-256, each canonical database identity, exact bytes/SHA-256 and SQLite integrity. It performs no restoration. Run the operator program with the packaged library directory on its classpath, not with an older app's classes. Do not launch Forge between validation and restore.

While holding that lock, create a uniquely named recovery directory **inside the same root**. Move each current database and its matching `-wal`, `-shm` and `-journal` sidecars into the recovery directory, preserving relative paths. Never copy an old main database on top of live newer WAL/SHM. Copy the verified snapshot to a new sibling `.restore.partial`, flush its file channel, verify its hash against the receipt, and atomically rename it into the now-vacant canonical database path. Do not replace or delete any original; preserve recovery files, receipts and external data. If a step fails, stop and repair the complete inventory under the lock before launching. The helper intentionally does not expose an automatic restore command because a metadata-only rollback can omit newer external work.

After restoration, launch the same or a newer version than the root's maximum recorded version; normal schema upgrades run against the restored databases. Restoring a pre-upgrade database does **not** lower the floor. To run an older binary, use a separately preserved, complete compatible pre-upgrade root and qualified matching installer, with its original receipts, annotations, settings and artifacts. Do not delete newer version receipts or mix newer database sidecars into that root. Changes made after the snapshot remain only in the preserved newer root/recovery inventory until deliberately reconciled.

## Evidence boundary

`DataUpgradeRecoveryTest` exercises genuine SQLite databases with committed, uncheckpointed WAL, every namespace, upgrade/downgrade, versionless startup refusal before schema creation, receipt/snapshot tampering, corruption after an earlier successful copy, unavailable destination, orphaned WAL refusal, lock identity, path containment and real Windows junction rejection. Symbolic-link coverage is capability-gated on Windows hosts that prohibit link creation. These synthetic database checks do not qualify installer upgrade/rollback, power failure, Windows/macOS native artifact acceptance, signing or release publication.
