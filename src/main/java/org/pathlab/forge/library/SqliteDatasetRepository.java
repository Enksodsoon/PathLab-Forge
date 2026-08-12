package org.pathlab.forge.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public final class SqliteDatasetRepository implements DatasetRepository, AutoCloseable {
    private static final int SCHEMA_VERSION = 3;
    private final Connection connection;

    public SqliteDatasetRepository(Path database, Path legacyProperties) throws IOException {
        try {
            var normalized = database.toAbsolutePath().normalize();
            Files.createDirectories(normalized.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
            configure();
            createSchema();
            migrateLegacy(legacyProperties.toAbsolutePath().normalize());
            recoverInterruptedConversions();
        } catch (SQLException error) {
            throw new IOException("Unable to open Forge SQLite library", error);
        }
    }

    private void configure() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private void createSchema() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS forge_meta (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS datasets (
                      id TEXT PRIMARY KEY,
                      display_name TEXT NOT NULL,
                      source_path TEXT NOT NULL UNIQUE,
                      source_bytes INTEGER NOT NULL,
                      format TEXT NOT NULL,
                      status TEXT NOT NULL,
                      detail TEXT NOT NULL,
                      output_path TEXT NOT NULL,
                      sha256 TEXT NOT NULL,
                      selected_series INTEGER NOT NULL,
                      width INTEGER NOT NULL,
                      height INTEGER NOT NULL,
                      downsample REAL NOT NULL,
                      estimated_output_bytes INTEGER NOT NULL,
                      crop_x INTEGER NOT NULL,
                      crop_y INTEGER NOT NULL,
                      crop_width INTEGER NOT NULL,
                      crop_height INTEGER NOT NULL,
                      source_fingerprint TEXT NOT NULL,
                      source_inventory TEXT NOT NULL,
                      configuration_revision TEXT NOT NULL,
                      current_artifact_revision TEXT NOT NULL,
                      approved_artifact_revision TEXT NOT NULL,
                      workspace_revision INTEGER NOT NULL DEFAULT 1
                    )""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS conversion_queue (
                      dataset_id TEXT PRIMARY KEY,
                      queue_position INTEGER NOT NULL UNIQUE,
                      configuration_revision TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      requested_format TEXT NOT NULL DEFAULT 'PREPARED_DZI_V2',
                      wait_reason TEXT NOT NULL
                    )""");
            var hasRequestedFormat = false;
            try (var columns = statement.executeQuery("PRAGMA table_info(conversion_queue)")) {
                while (columns.next()) {
                    hasRequestedFormat |= "requested_format".equals(columns.getString("name"));
                }
            }
            if (!hasRequestedFormat) {
                statement.execute(
                        "ALTER TABLE conversion_queue ADD COLUMN requested_format "
                                + "TEXT NOT NULL DEFAULT 'PREPARED_DZI_V2'");
            }
            statement.execute("""
                    INSERT INTO forge_meta(key, value) VALUES ('schema_version', '%d')
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value
                    """.formatted(SCHEMA_VERSION));
        }
    }

    private void migrateLegacy(Path legacy) throws IOException, SQLException {
        if (!Files.isRegularFile(legacy) || migrationComplete()) {
            return;
        }
        var backup = legacy.resolveSibling(legacy.getFileName() + ".pre-sqlite-backup");
        Files.copy(legacy, backup, StandardCopyOption.REPLACE_EXISTING);
        var oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (var dataset : new PropertiesDatasetRepository(legacy).list()) {
                upsert(dataset);
            }
            try (var statement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO forge_meta(key, value) VALUES ('legacy_migrated', '1')")) {
                statement.executeUpdate();
            }
            connection.commit();
        } catch (IOException | SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private boolean migrationComplete() throws SQLException {
        try (var statement = connection.prepareStatement(
                        "SELECT value FROM forge_meta WHERE key='legacy_migrated'");
                var rows = statement.executeQuery()) {
            return rows.next() && "1".equals(rows.getString(1));
        }
    }

    private void recoverInterruptedConversions() throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE datasets
                SET status=?,
                    detail=?,
                    workspace_revision=workspace_revision+1
                WHERE status IN (?,?,?,?)
                """)) {
            statement.setString(1, DatasetStatus.FAILED.name());
            statement.setString(2, "Interrupted by application restart; retry is safe");
            statement.setString(3, DatasetStatus.INSPECTING.name());
            statement.setString(4, DatasetStatus.CONVERTING.name());
            statement.setString(5, DatasetStatus.OPTIMIZING_OME.name());
            statement.setString(6, DatasetStatus.VALIDATING.name());
            statement.executeUpdate();
        }
    }

    public synchronized String journalMode() throws IOException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA journal_mode")) {
            return rows.next() ? rows.getString(1) : "";
        } catch (SQLException error) {
            throw new IOException("Unable to read SQLite journal mode", error);
        }
    }

    @Override
    public synchronized List<LocalDataset> list() {
        try (var statement = connection.prepareStatement("SELECT * FROM datasets");
                var rows = statement.executeQuery()) {
            var datasets = new ArrayList<LocalDataset>();
            while (rows.next()) {
                datasets.add(read(rows));
            }
            datasets.sort(Comparator.comparing(LocalDataset::displayName).thenComparing(LocalDataset::id));
            return List.copyOf(datasets);
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to list Forge datasets", error);
        }
    }

    @Override
    public synchronized Optional<LocalDataset> find(String id) {
        return findOne("SELECT * FROM datasets WHERE id=?", id);
    }

    @Override
    public synchronized Optional<LocalDataset> findBySourcePath(String sourcePath) {
        return findOne("SELECT * FROM datasets WHERE source_path=?", sourcePath);
    }

    private Optional<LocalDataset> findOne(String sql, String value) {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to query Forge dataset", error);
        }
    }

    @Override
    public synchronized void save(LocalDataset dataset) throws IOException {
        try {
            upsert(dataset);
        } catch (SQLException error) {
            throw new IOException("Unable to save Forge dataset", error);
        }
    }

    @Override
    public synchronized LocalDataset update(String id, UnaryOperator<LocalDataset> change)
            throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Dataset was not found"));
        var updated = change.apply(current);
        save(updated);
        return updated;
    }

    private void upsert(LocalDataset dataset) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO datasets VALUES (
                  ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1
                )
                ON CONFLICT(id) DO UPDATE SET
                  display_name=excluded.display_name,
                  source_path=excluded.source_path,
                  source_bytes=excluded.source_bytes,
                  format=excluded.format,
                  status=excluded.status,
                  detail=excluded.detail,
                  output_path=excluded.output_path,
                  sha256=excluded.sha256,
                  selected_series=excluded.selected_series,
                  width=excluded.width,
                  height=excluded.height,
                  downsample=excluded.downsample,
                  estimated_output_bytes=excluded.estimated_output_bytes,
                  crop_x=excluded.crop_x,
                  crop_y=excluded.crop_y,
                  crop_width=excluded.crop_width,
                  crop_height=excluded.crop_height,
                  source_fingerprint=excluded.source_fingerprint,
                  source_inventory=excluded.source_inventory,
                  configuration_revision=excluded.configuration_revision,
                  current_artifact_revision=excluded.current_artifact_revision,
                  approved_artifact_revision=excluded.approved_artifact_revision,
                  workspace_revision=datasets.workspace_revision+1
                """)) {
            bind(statement, dataset);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, LocalDataset dataset) throws SQLException {
        int index = 1;
        statement.setString(index++, dataset.id());
        statement.setString(index++, dataset.displayName());
        statement.setString(index++, dataset.sourcePath());
        statement.setLong(index++, dataset.sourceBytes());
        statement.setString(index++, dataset.format().name());
        statement.setString(index++, dataset.status().name());
        statement.setString(index++, dataset.detail());
        statement.setString(index++, dataset.outputPath());
        statement.setString(index++, dataset.sha256());
        statement.setInt(index++, dataset.selectedSeries());
        statement.setInt(index++, dataset.width());
        statement.setInt(index++, dataset.height());
        statement.setDouble(index++, dataset.downsample());
        statement.setLong(index++, dataset.estimatedOutputBytes());
        statement.setInt(index++, dataset.cropX());
        statement.setInt(index++, dataset.cropY());
        statement.setInt(index++, dataset.cropWidth());
        statement.setInt(index++, dataset.cropHeight());
        statement.setString(index++, dataset.sourceFingerprint());
        statement.setString(index++, dataset.sourceInventory());
        statement.setString(index++, dataset.configurationRevision());
        statement.setString(index++, dataset.currentArtifactRevision());
        statement.setString(index, dataset.approvedArtifactRevision());
    }

    private static LocalDataset read(ResultSet rows) throws SQLException {
        return new LocalDataset(
                rows.getString("id"),
                rows.getString("display_name"),
                rows.getString("source_path"),
                rows.getLong("source_bytes"),
                DatasetFormat.valueOf(rows.getString("format")),
                DatasetStatus.valueOf(rows.getString("status")),
                rows.getString("detail"),
                rows.getString("output_path"),
                rows.getString("sha256"),
                rows.getInt("selected_series"),
                rows.getInt("width"),
                rows.getInt("height"),
                rows.getDouble("downsample"),
                rows.getLong("estimated_output_bytes"),
                rows.getInt("crop_x"),
                rows.getInt("crop_y"),
                rows.getInt("crop_width"),
                rows.getInt("crop_height"),
                rows.getString("source_fingerprint"),
                rows.getString("source_inventory"),
                rows.getString("configuration_revision"),
                rows.getString("current_artifact_revision"),
                rows.getString("approved_artifact_revision"));
    }

    @Override
    public synchronized void delete(String id) throws IOException {
        try (var queue = connection.prepareStatement("DELETE FROM conversion_queue WHERE dataset_id=?");
                var statement = connection.prepareStatement("DELETE FROM datasets WHERE id=?")) {
            queue.setString(1, id);
            queue.executeUpdate();
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to delete Forge dataset", error);
        }
    }

    @Override
    public synchronized List<ConversionQueueEntry> listQueueEntries() {
        try (var statement = connection.prepareStatement(
                        "SELECT * FROM conversion_queue ORDER BY queue_position");
                var rows = statement.executeQuery()) {
            var entries = new ArrayList<ConversionQueueEntry>();
            while (rows.next()) {
                entries.add(new ConversionQueueEntry(
                        rows.getString("dataset_id"),
                        rows.getLong("queue_position"),
                        rows.getString("configuration_revision"),
                        rows.getLong("created_at"),
                        rows.getString("requested_format"),
                        rows.getString("wait_reason")));
            }
            return List.copyOf(entries);
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to read conversion queue", error);
        }
    }

    @Override
    public synchronized void saveQueueEntry(ConversionQueueEntry entry) throws IOException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO conversion_queue(
                  dataset_id, queue_position, configuration_revision, created_at,
                  requested_format, wait_reason) VALUES (?,?,?,?,?,?)
                ON CONFLICT(dataset_id) DO UPDATE SET
                  queue_position=excluded.queue_position,
                  configuration_revision=excluded.configuration_revision,
                  created_at=excluded.created_at,
                  requested_format=excluded.requested_format,
                  wait_reason=excluded.wait_reason
                """)) {
            statement.setString(1, entry.datasetId());
            statement.setLong(2, entry.position());
            statement.setString(3, entry.configurationRevision());
            statement.setLong(4, entry.createdAt());
            statement.setString(5, entry.requestedFormat());
            statement.setString(6, entry.waitReason());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to save conversion queue", error);
        }
    }

    @Override
    public synchronized void deleteQueueEntry(String datasetId) throws IOException {
        try (var statement = connection.prepareStatement(
                "DELETE FROM conversion_queue WHERE dataset_id=?")) {
            statement.setString(1, datasetId);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to update conversion queue", error);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException error) {
            throw new IOException("Unable to close Forge SQLite library", error);
        }
    }
}
