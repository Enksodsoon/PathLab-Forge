package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.ForgePaths;

final class DataUpgradeRecoveryTest {
    @TempDir Path root;
    private Connection database(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE records (id INTEGER PRIMARY KEY, value TEXT)");
            statement.execute("INSERT INTO records VALUES (42,'draft and annotations')");
        }
        return connection;
    }
    private String value(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var query = connection.createStatement().executeQuery("SELECT value FROM records WHERE id=42")) {
            assertTrue(query.next()); return query.getString(1);
        }
    }

    @Test void snapshotsCommittedWalAcrossEveryManagedDatabaseBeforeUpgrade() throws Exception {
        var files = List.of("forge.db", "forge.db." + "a".repeat(64) + ".db",
                "viewer-sync.db", "viewer-sync.db." + "b".repeat(64) + ".db",
                "managed/analysis-runs.sqlite", "managed/study-authoring.sqlite");
        var connections = new ArrayList<Connection>();
        try {
            for (var name : files) connections.add(database(root.resolve(name)));
            assertTrue(Files.size(root.resolve("forge.db-wal")) > 0);
            var databaseBefore = Files.readAllBytes(root.resolve("forge.db"));
            var walBefore = Files.readAllBytes(root.resolve("forge.db-wal"));
            Files.writeString(root.resolve("settings.properties"), "keep=true");
            Files.writeString(root.resolve("managed/source-marker"), "source unchanged");
            try (var lock = DataRootLock.acquire(root)) {
                var receipt = DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0-rc.1").orElseThrow();
                DataUpgradeRecovery.verifyBackup(root, lock, receipt);
                assertArrayEquals(databaseBefore, Files.readAllBytes(root.resolve("forge.db")));
                assertArrayEquals(walBefore, Files.readAllBytes(root.resolve("forge.db-wal")));
                for (var name : files) assertEquals("draft and annotations", value(receipt.getParent().resolve(name)));
                assertTrue(DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0-rc.1").isEmpty());
                assertEquals("keep=true", Files.readString(root.resolve("settings.properties")));
                assertEquals("source unchanged", Files.readString(root.resolve("managed/source-marker")));
                assertFalse(Files.exists(receipt.getParent().resolve("managed/source-marker")));
                var upgraded = DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0").orElseThrow();
                assertNotEquals(receipt, upgraded);
                assertEquals(DataUpgradeRecovery.DOWNGRADE_BLOCKED, assertThrows(DataUpgradeRecovery.Failure.class,
                        () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0-rc.2")).startupCode());
                assertEquals(DataUpgradeRecovery.VERSION_REQUIRED, assertThrows(DataUpgradeRecovery.Failure.class,
                        () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, null)).startupCode());
            }
        } finally { for (var connection : connections) connection.close(); }
    }

    @Test void corruptDatabaseStopsUpgradeAndRetainsEarlierCompletedSnapshotWithoutChangingOriginal() throws Exception {
        database(root.resolve("forge.db")).close();
        var before = Files.readAllBytes(root.resolve("forge.db"));
        database(root.resolve("viewer-sync.db")).close();
        try (var corrupt = new java.io.RandomAccessFile(root.resolve("viewer-sync.db").toFile(), "rw")) {
            corrupt.seek(4096); corrupt.writeByte(255);
        }
        var corruptBefore = Files.readAllBytes(root.resolve("viewer-sync.db"));
        try (var lock = DataRootLock.acquire(root)) {
            assertEquals(DataUpgradeRecovery.BACKUP_FAILED, assertThrows(DataUpgradeRecovery.Failure.class,
                    () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0")).startupCode());
            assertArrayEquals(before, Files.readAllBytes(root.resolve("forge.db")));
            assertArrayEquals(corruptBefore, Files.readAllBytes(root.resolve("viewer-sync.db")));
            try (var entries = Files.list(root.resolve("upgrade-backups"))) {
                var partial = entries.findFirst().orElseThrow();
                assertTrue(partial.getFileName().toString().endsWith(".partial"));
                assertEquals("draft and annotations", value(partial.resolve("forge.db")));
                assertFalse(Files.exists(partial.resolve("receipt.json")));
            }
        }
    }

    @Test void unusableBackupDestinationAndInvalidVersionsPreserveDatabase() throws Exception {
        database(root.resolve("forge.db")).close();
        var before = Files.readAllBytes(root.resolve("forge.db"));
        Files.writeString(root.resolve("upgrade-backups"), "not a directory");
        try (var lock = DataRootLock.acquire(root)) {
            assertThrows(IOException.class, () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0"));
            assertThrows(IOException.class, () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "invalid"));
            assertArrayEquals(before, Files.readAllBytes(root.resolve("forge.db")));
        }
    }

    @Test void receiptAndSnapshotTamperingAreRefusedAndClosedOrWrongRootLocksAreRejected() throws Exception {
        database(root.resolve("forge.db")).close();
        Path receipt;
        try (var lock = DataRootLock.acquire(root)) {
            receipt = DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0").orElseThrow();
            Files.writeString(receipt.getParent().resolve("forge.db"), "tamper");
            assertThrows(IOException.class, () -> DataUpgradeRecovery.verifyBackup(root, lock, receipt));
            Files.writeString(receipt, "{}");
            assertEquals(DataUpgradeRecovery.RECOVERY_REQUIRED, assertThrows(DataUpgradeRecovery.Failure.class,
                    () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.1.0")).startupCode());
            assertThrows(IOException.class, () -> DataUpgradeRecovery.prepare(ForgePaths.at(root.resolve("other")), lock, "1.0.0"));
        }
        var closed = DataRootLock.acquire(root); closed.close();
        assertThrows(IOException.class, () -> DataUpgradeRecovery.verifyBackup(root, closed, receipt));
    }

    @Test void startupRefusesVersionlessInstalledDataBeforeSchemasAndKeepsPrivatePipeActionable() throws Exception {
        database(root.resolve("forge.db")).close();
        try (var lock = DataRootLock.acquire(root)) {
            DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0");
        }
        var before = Files.readAllBytes(root.resolve("forge.db"));
        var original = System.out;
        var desktop = System.getProperty("pathlab.forge.desktop");
        var port = System.getProperty("pathlab.forge.port");
        var output = new java.io.ByteArrayOutputStream();
        try (var pipe = new java.io.PrintStream(output)) {
            System.setOut(pipe);
            org.pathlab.forge.ForgeApp.main(new String[] {"--desktop", "--data-root", root.toString()});
        } finally {
            System.setOut(original);
            if (desktop == null) System.clearProperty("pathlab.forge.desktop"); else System.setProperty("pathlab.forge.desktop", desktop);
            if (port == null) System.clearProperty("pathlab.forge.port"); else System.setProperty("pathlab.forge.port", port);
        }
        var status = output.toString(java.nio.charset.StandardCharsets.UTF_8).strip();
        assertTrue(status.startsWith("PATHLAB_FORGE_FAILED "));
        var document = new com.fasterxml.jackson.databind.ObjectMapper().readTree(status.substring("PATHLAB_FORGE_FAILED ".length()));
        assertEquals(2, document.size());
        assertEquals(1, document.path("protocol").asInt());
        assertEquals(DataUpgradeRecovery.VERSION_REQUIRED, document.path("code").asText());
        assertArrayEquals(before, Files.readAllBytes(root.resolve("forge.db")));
        // Self-test reads runtime/source evidence without raising the installed-data version floor.
        var failure = assertThrows(IOException.class, () -> org.pathlab.forge.ForgeApp.main(
                new String[] {"--reader-self-test", "--data-root", root.toString()}));
        assertFalse(failure instanceof DataUpgradeRecovery.Failure);
        assertArrayEquals(before, Files.readAllBytes(root.resolve("forge.db")));
        try (var pipe = new java.io.PrintStream(new java.io.ByteArrayOutputStream())) {
            for (var code : List.of(DataUpgradeRecovery.BACKUP_FAILED, DataUpgradeRecovery.DOWNGRADE_BLOCKED,
                    DataUpgradeRecovery.VERSION_REQUIRED, DataUpgradeRecovery.RECOVERY_REQUIRED)) DesktopStartup.failed(pipe, code);
            assertThrows(IllegalArgumentException.class, () -> DesktopStartup.failed(pipe, "private-path-or-token"));
        }
    }

    @Test void rejectsEscapingPathsWithoutChangingExternalFiles() throws Exception {
        var outside = Files.createTempFile("forge-upgrade-outside", ".db");
        try (var lock = DataRootLock.acquire(root)) {
            assertThrows(IOException.class, () -> DataUpgradeRecovery.prepare(new ForgePaths(root, outside, root.resolve("managed")), lock, "1.0.0"));
            assertEquals(0, Files.size(outside));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void rejectsSymlinkDatabase() throws Exception {
        var outside = Files.createTempFile("forge-upgrade-outside", ".db");
        try {
            Files.createSymbolicLink(root.resolve("forge.db"), outside);
        } catch (IOException | UnsupportedOperationException error) {
            Files.deleteIfExists(outside);
            org.junit.jupiter.api.Assumptions.abort("Host does not permit symbolic links");
        }
        try (var lock = DataRootLock.acquire(root)) {
            assertThrows(IOException.class, () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0"));
            assertThrows(IOException.class, () -> DataUpgradeRecovery.prepare(new ForgePaths(root, outside, root.resolve("managed")), lock, "1.0.0"));
            assertEquals(0, Files.size(outside));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void sqlitePathsWithSpacesQuotesAndUriPunctuationRemainInsideRoot() throws Exception {
        var special = root.resolve("spaces ' # and unicode ค");
        database(special.resolve("forge.db")).close();
        try (var lock = DataRootLock.acquire(special)) {
            var receipt = DataUpgradeRecovery.prepare(ForgePaths.at(special), lock, "1.0.0").orElseThrow();
            DataUpgradeRecovery.verifyBackup(special, lock, receipt);
            assertEquals("draft and annotations", value(receipt.getParent().resolve("forge.db")));
        }
    }

    @Test void orphanedWalCannotBeSilentlyReplacedByANewDatabase() throws Exception {
        Files.writeString(root.resolve("forge.db-wal"), "retain interrupted data");
        try (var lock = DataRootLock.acquire(root)) {
            assertThrows(DataUpgradeRecovery.Failure.class, () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0"));
            assertEquals("retain interrupted data", Files.readString(root.resolve("forge.db-wal")));
            assertFalse(Files.exists(root.resolve("forge.db")));
        }
    }

    @Test void windowsJunctionCannotRedirectManagedDatabaseBackups() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var outside = Files.createTempDirectory("forge-upgrade-junction");
        var junction = root.resolve("managed");
        try {
            var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assumptions.assumeTrue(process.exitValue() == 0, "Host does not permit directory junctions");
            Files.writeString(outside.resolve("preserved.txt"), "outside remains unchanged");
            try (var lock = DataRootLock.acquire(root)) {
                assertThrows(DataUpgradeRecovery.Failure.class, () -> DataUpgradeRecovery.prepare(ForgePaths.at(root), lock, "1.0.0"));
                assertEquals("outside remains unchanged", Files.readString(outside.resolve("preserved.txt")));
            }
        } finally {
            Files.deleteIfExists(junction);
            Files.deleteIfExists(outside.resolve("preserved.txt"));
            Files.deleteIfExists(outside);
        }
    }
}
