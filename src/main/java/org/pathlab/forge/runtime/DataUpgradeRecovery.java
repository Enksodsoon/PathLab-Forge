package org.pathlab.forge.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.pathlab.forge.feature.SemanticVersion;
import org.pathlab.forge.library.ForgePaths;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

/** Pre-schema, WAL-aware backups and conservative version floors. Never restores or deletes data. */
public final class DataUpgradeRecovery {
    public static final String BACKUP_FAILED = "DATA_UPGRADE_BACKUP_FAILED";
    public static final String DOWNGRADE_BLOCKED = "DATA_DOWNGRADE_BLOCKED";
    public static final String VERSION_REQUIRED = "DATA_VERSION_REQUIRED";
    public static final String RECOVERY_REQUIRED = "DATA_UPGRADE_RECOVERY_REQUIRED";
    /** Only this fixed code crosses the private startup pipe; diagnostic messages stay local. */
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        private final String startupCode;
        private Failure(String startupCode, String message, Throwable cause) {
            super(message, cause); this.startupCode = startupCode;
        }
        public String startupCode() { return startupCode; }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_DATABASES = 512, MAX_RECEIPTS = 1024, MAX_RECEIPT_BYTES = 1024 * 1024;
    private static final String SCHEMA = "pathlab.forge.data-backup/1";
    private DataUpgradeRecovery() {}

    /** Called under the application lock before any store constructor/schema write.
     * Null version is a development build: allowed only on roots without installed-version receipts.
     * Returns a new immutable receipt on first installed use/upgrade; never overwrites old backups.
     */
    public static Optional<Path> prepare(ForgePaths paths, DataRootLock lock, String version) throws IOException {
        try { return prepareLocked(paths, lock, version); }
        catch (Failure error) { throw error; }
        catch (IOException | RuntimeException error) {
            throw new Failure(BACKUP_FAILED, "Forge stopped before schema writes because the upgrade backup could not be completed", error);
        }
    }

    private static Optional<Path> prepareLocked(ForgePaths paths, DataRootLock lock, String version) throws IOException {
        var root = paths.dataRoot();
        lock.requireHeld(root);
        if (!paths.repositoryFile().equals(root.resolve("forge.db")) || !paths.managedRoot().equals(root.resolve("managed")))
            throw new IOException("Upgrade recovery requires databases inside the standard Forge data root");
        var backupRoot = root.resolve("upgrade-backups");
        DataRootLock.requireSafePath(backupRoot);
        String maximum = "";
        int historyCount = 0;
        if (Files.exists(backupRoot, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(backupRoot, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Forge backup directory");
            try (var entries = Files.newDirectoryStream(backupRoot)) {
                for (var entry : entries) {
                    if (++historyCount > MAX_RECEIPTS) throw new IOException("Forge backup history exceeds its inspection bound");
                    DataRootLock.requireSafePath(entry);
                    if (entry.getFileName().toString().matches("snapshot-[0-9a-f-]{36}\\.partial")) continue;
                    if (!entry.getFileName().toString().matches("snapshot-[0-9a-f-]{36}")) throw new IOException("Unrecognized Forge backup history entry");
                    final JsonNode receipt;
                    try { receipt = readReceipt(entry.resolve("receipt.json")); }
                    catch (IOException error) { throw new Failure(RECOVERY_REQUIRED, "Forge backup history requires operator recovery", error); }
                    var recorded = receipt.path("maximumAppVersion").asText();
                    if (maximum.isEmpty() || SemanticVersion.compare(recorded, maximum) > 0) maximum = recorded;
                }
            }
        }
        // Validate path/sidecar safety on every start, including an unchanged installed version.
        // Otherwise SQLite may recreate a missing main database over a surviving committed WAL.
        var databases = databases(root);
        if (version == null) {
            if (!maximum.isEmpty()) throw new Failure(VERSION_REQUIRED, "A versionless development build cannot open installed Forge data", null);
            return Optional.empty();
        }
        if (!SemanticVersion.valid(version)) throw new Failure(VERSION_REQUIRED, "Installed Forge version is missing or invalid", null);
        if (!maximum.isEmpty()) {
            int compared = SemanticVersion.compare(version, maximum);
            if (compared < 0) throw new Failure(DOWNGRADE_BLOCKED, "Unsupported Forge downgrade: this data root requires " + maximum + " or newer", null);
            if (compared == 0) return Optional.empty();
        }
        if (historyCount >= MAX_RECEIPTS) throw new IOException("Forge backup history is full; preserve it before operator maintenance");
        long total = 0;
        for (var database : databases) {
            try (var connection = readOnly(database)) {
                long size = Math.multiplyExact(pragma(connection, "page_count"), pragma(connection, "page_size"));
                if (size > MAX_DATABASE_BYTES) throw new IOException("Forge metadata database exceeds the 2 GiB backup bound");
                total = Math.addExact(total, size);
            } catch (SQLException | ArithmeticException error) { throw new IOException("Unable to inspect Forge database for backup", error); }
            if (total > MAX_TOTAL_BYTES) throw new IOException("Forge metadata exceeds the 8 GiB backup bound");
        }
        if (Files.getFileStore(root).getUsableSpace() < total + Math.max(1024 * 1024, total / 5))
            throw new IOException("Insufficient disk space for verified pre-upgrade Forge backups");
        Files.createDirectories(backupRoot);
        var name = "snapshot-" + UUID.randomUUID();
        var staging = backupRoot.resolve(name + ".partial");
        Files.createDirectory(staging);
        var receipt = JSON.createObjectNode();
        receipt.put("schema", SCHEMA);
        receipt.put("maximumAppVersion", version);
        // ponytail: refuse every older binary; permit older versions only with qualified schema compatibility.
        receipt.put("minimumRollbackVersion", version);
        receipt.put("previousAppVersion", maximum.isEmpty() ? "unversioned" : maximum);
        receipt.put("createdAt", Instant.now().toString());
        var records = receipt.putArray("databases");
        long copiedBytes = 0;
        for (var database : databases) {
            lock.requireHeld(root);
            var relative = root.relativize(database);
            var target = staging.resolve(relative);
            Files.createDirectories(target.getParent());
            snapshot(database, target);
            long copied = Files.size(target);
            copiedBytes += copied;
            if (copied > MAX_DATABASE_BYTES || copiedBytes > MAX_TOTAL_BYTES)
                throw new IOException("Forge backup grew beyond its verified byte bound");
            var record = records.addObject();
            record.put("path", relative.toString().replace('\\', '/'));
            record.put("bytes", Files.size(target));
            record.put("sha256", sha256(target));
            record.put("integrity", "ok");
        }
        var receiptFile = staging.resolve("receipt.json");
        writeDurable(receiptFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
        writeDurable(staging.resolve("receipt.sha256"), sha256(receiptFile).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        verifyBackup(root, lock, receiptFile);
        var published = backupRoot.resolve(name);
        Files.move(staging, published, StandardCopyOption.ATOMIC_MOVE);
        return Optional.of(published.resolve("receipt.json"));
    }

    /** Read-only operator validation. Requires the root lock; verifies receipt, bytes, SHA-256 and SQLite integrity. */
    public static void verifyBackup(Path dataRoot, DataRootLock lock, Path receiptFile) throws IOException {
        var root = dataRoot.toAbsolutePath().normalize();
        lock.requireHeld(root);
        var normalized = receiptFile.toAbsolutePath().normalize();
        if (!normalized.startsWith(root.resolve("upgrade-backups")) || !normalized.getFileName().toString().equals("receipt.json")
                || normalized.getParent().getParent() == null || !normalized.getParent().getParent().equals(root.resolve("upgrade-backups")))
            throw new IOException("Backup receipt is outside the Forge backup directory");
        var receipt = readReceipt(normalized);
        for (var record : receipt.path("databases")) {
            var database = normalized.getParent().resolve(record.path("path").asText());
            regular(database);
            if (Files.size(database) != record.path("bytes").asLong() || !sha256(database).equals(record.path("sha256").asText()))
                throw new IOException("Forge backup bytes failed verification");
            try (var connection = readOnly(database)) { integrity(connection); }
            catch (SQLException error) { throw new IOException("Forge backup failed SQLite integrity verification", error); }
        }
    }

    private static JsonNode readReceipt(Path file) throws IOException {
        regular(file);
        var digestFile = file.resolveSibling("receipt.sha256");
        regular(digestFile);
        if (Files.size(file) > MAX_RECEIPT_BYTES || Files.size(digestFile) != 64
                || !sha256(file).equals(Files.readString(digestFile))) throw new IOException("Forge backup receipt failed integrity verification");
        var receipt = JSON.readTree(Files.readAllBytes(file));
        if (receipt == null) throw new IOException("Invalid empty Forge backup receipt");
        var version = receipt.path("maximumAppVersion").asText();
        if (!SCHEMA.equals(receipt.path("schema").asText()) || !SemanticVersion.valid(version)
                || !version.equals(receipt.path("minimumRollbackVersion").asText())
                || !receipt.path("databases").isArray() || receipt.path("databases").size() > MAX_DATABASES)
            throw new IOException("Invalid Forge backup receipt");
        var seen = new HashSet<String>();
        long total = 0;
        for (var record : receipt.path("databases")) {
            var path = record.path("path").asText();
            long bytes = record.path("bytes").asLong(-1);
            if (!databaseName(path) || !seen.add(path) || !record.path("bytes").isIntegralNumber()
                    || !record.path("bytes").canConvertToLong() || bytes < 0 || bytes > MAX_DATABASE_BYTES
                    || !record.path("sha256").asText().matches("[0-9a-f]{64}") || !"ok".equals(record.path("integrity").asText()))
                throw new IOException("Invalid Forge backup database identity");
            total += bytes;
            if (total > MAX_TOTAL_BYTES) throw new IOException("Forge backup receipt exceeds its byte bound");
        }
        return receipt;
    }

    private static boolean databaseName(String name) {
        return name.matches("(forge|viewer-sync)\\.db(\\.[0-9a-f]{64}\\.db)?")
                || Set.of("managed/analysis-runs.sqlite", "managed/study-authoring.sqlite").contains(name);
    }

    private static List<Path> databases(Path root) throws IOException {
        var files = new ArrayList<Path>();
        for (var directory : List.of(root, root.resolve("managed"))) {
            DataRootLock.requireSafePath(directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Forge managed directory");
            try (var entries = Files.newDirectoryStream(directory)) {
                for (var entry : entries) {
                    var name = root.relativize(entry).toString().replace('\\', '/');
                    for (var suffix : List.of("-wal", "-shm", "-journal")) {
                        if (name.endsWith(suffix) && databaseName(name.substring(0, name.length() - suffix.length()))
                                && !Files.exists(entry.resolveSibling(entry.getFileName().toString().substring(0,
                                        entry.getFileName().toString().length() - suffix.length())), LinkOption.NOFOLLOW_LINKS))
                            throw new IOException("Orphaned Forge SQLite sidecar requires recovery before startup");
                    }
                    if (!databaseName(name)) continue;
                    regular(entry);
                    for (var suffix : List.of("-wal", "-shm", "-journal")) {
                        var sidecar = entry.resolveSibling(entry.getFileName() + suffix);
                        if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) regular(sidecar);
                    }
                    if (files.size() >= MAX_DATABASES) throw new IOException("Forge database count exceeds its backup bound");
                    files.add(entry);
                }
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static Connection readOnly(Path database) throws SQLException, IOException {
        regular(database);
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setOpenMode(org.sqlite.SQLiteOpenMode.OPEN_URI);
        config.setBusyTimeout(5000);
        config.setCacheSize(256);
        return config.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString());
    }

    private static long pragma(Connection connection, String name) throws SQLException {
        try (var query = connection.createStatement(); var result = query.executeQuery("PRAGMA " + name)) {
            if (!result.next()) throw new SQLException("Missing SQLite size metadata");
            return result.getLong(1);
        }
    }

    private static void integrity(Connection connection) throws SQLException, IOException {
        try (var query = connection.createStatement(); var result = query.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next()) throw new IOException("Forge database failed SQLite integrity verification");
        }
    }

    private static void snapshot(Path database, Path target) throws IOException {
        try (var source = readOnly(database)) {
            integrity(source);
            // Native backup preserves rowids and includes committed WAL pages without modifying the source schema.
            int result = ((SQLiteConnection) source).getDatabase().backup("main", target.toString(), null, 50, 100, 128);
            if (result != 0) throw new IOException("Forge SQLite backup failed (" + result + ")");
        } catch (SQLException error) { throw new IOException("Unable to create Forge SQLite backup", error); }
        try (var file = FileChannel.open(target, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { file.force(true); }
        try (var copied = readOnly(target)) { integrity(copied); }
        catch (SQLException error) { throw new IOException("Unable to verify Forge SQLite backup", error); }
    }

    private static void regular(Path file) throws IOException {
        DataRootLock.requireSafePath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Forge maintenance requires a regular file: " + file);
    }

    private static void writeDurable(Path file, byte[] bytes) throws IOException {
        try (var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = new byte[1024 * 1024];
                for (int read; (read = stream.read(buffer)) != -1;) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
