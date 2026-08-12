package org.pathlab.forge.viewer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class SqliteViewerSyncStore implements ViewerSyncStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection connection;

    public SqliteViewerSyncStore(Path database) throws IOException {
        try {
            var normalized = database.toAbsolutePath().normalize();
            Files.createDirectories(normalized.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS viewer_sync_records (
                          slide_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, folder_id TEXT NOT NULL,
                          status TEXT NOT NULL, content_revision INTEGER NOT NULL,
                          annotation_revision INTEGER NOT NULL, metadata_revision INTEGER NOT NULL,
                          folder_revision INTEGER NOT NULL, thumbnail_url TEXT NOT NULL, tile_url TEXT NOT NULL,
                          remote_updated_at TEXT NOT NULL, dirty_fields TEXT NOT NULL DEFAULT '',
                          partial_path TEXT NOT NULL DEFAULT '', download_bytes INTEGER NOT NULL DEFAULT 0,
                          download_offset INTEGER NOT NULL DEFAULT 0, download_sha256 TEXT NOT NULL DEFAULT '',
                          content_bytes INTEGER NOT NULL DEFAULT 0, content_sha256 TEXT NOT NULL DEFAULT '',
                          metadata_json TEXT NOT NULL DEFAULT '{}')
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS viewer_remote_folders (
                          folder_id TEXT PRIMARY KEY, name TEXT NOT NULL, parent_id TEXT NOT NULL,
                          revision INTEGER NOT NULL)
                        """);
                ensureColumn(connection, "viewer_sync_records", "content_bytes",
                        "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(connection, "viewer_sync_records", "content_sha256",
                        "TEXT NOT NULL DEFAULT ''");
                ensureColumn(connection, "viewer_sync_records", "metadata_json",
                        "TEXT NOT NULL DEFAULT '{}'");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS viewer_sync_state (
                          singleton INTEGER PRIMARY KEY CHECK(singleton=1), cursor INTEGER NOT NULL)
                        """);
                statement.execute("INSERT OR IGNORE INTO viewer_sync_state VALUES (1,0)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS viewer_sync_conflicts (
                          slide_id TEXT NOT NULL, field TEXT NOT NULL, local_value TEXT NOT NULL,
                          remote_value TEXT NOT NULL, remote_revision INTEGER NOT NULL,
                          detected_at TEXT NOT NULL, unresolved INTEGER NOT NULL,
                          PRIMARY KEY(slide_id,field))
                        """);
            }
        } catch (SQLException error) {
            throw new IOException("Unable to open Viewer sync store", error);
        }
    }

    private static void ensureColumn(Connection connection, String table, String column,
            String definition) throws SQLException {
        try (var columns = connection.createStatement().executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) return;
            }
        }
        connection.createStatement().execute(
                "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    @Override
    public synchronized void upsertRemote(ViewerRemoteSlide slide) throws IOException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO viewer_sync_records
                (slide_id,display_name,folder_id,status,content_revision,annotation_revision,
                 metadata_revision,folder_revision,thumbnail_url,tile_url,remote_updated_at,
                 content_bytes,content_sha256,metadata_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(slide_id) DO UPDATE SET display_name=excluded.display_name,
                  folder_id=excluded.folder_id,status=excluded.status,
                  content_revision=excluded.content_revision,annotation_revision=excluded.annotation_revision,
                  metadata_revision=excluded.metadata_revision,folder_revision=excluded.folder_revision,
                  thumbnail_url=excluded.thumbnail_url,tile_url=excluded.tile_url,
                  remote_updated_at=excluded.remote_updated_at,content_bytes=excluded.content_bytes,
                  content_sha256=excluded.content_sha256,metadata_json=excluded.metadata_json
                """)) {
            statement.setString(1, slide.id());
            statement.setString(2, slide.displayName());
            statement.setString(3, slide.folderId());
            statement.setString(4, slide.status());
            statement.setLong(5, slide.contentRevision());
            statement.setLong(6, slide.annotationRevision());
            statement.setLong(7, slide.metadataRevision());
            statement.setLong(8, slide.folderRevision());
            statement.setString(9, slide.thumbnailUrl());
            statement.setString(10, slide.tileUrl());
            statement.setString(11, slide.updatedAt().toString());
            statement.setLong(12, slide.contentBytes());
            statement.setString(13, slide.contentSha256());
            statement.setString(14, JSON.writeValueAsString(slide.metadata()));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to save remote slide", error);
        }
    }

    @Override
    public synchronized void replaceFolders(List<ViewerRemoteFolder> folders) throws IOException {
        try {
            connection.setAutoCommit(false);
            try (var delete = connection.prepareStatement("DELETE FROM viewer_remote_folders")) {
                delete.executeUpdate();
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO viewer_remote_folders VALUES (?,?,?,?)")) {
                for (var folder : folders) {
                    insert.setString(1, folder.id());
                    insert.setString(2, folder.name());
                    insert.setString(3, folder.parentId());
                    insert.setLong(4, folder.revision());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException error) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            throw new IOException("Unable to save remote folders", error);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException error) {
                throw new IOException("Unable to restore sync store transaction mode", error);
            }
        }
    }

    @Override
    public synchronized List<ViewerRemoteFolder> folders() throws IOException {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM viewer_remote_folders ORDER BY name,folder_id");
                var rows = statement.executeQuery()) {
            var folders = new ArrayList<ViewerRemoteFolder>();
            while (rows.next()) {
                folders.add(new ViewerRemoteFolder(rows.getString("folder_id"), rows.getString("name"),
                        rows.getString("parent_id"), rows.getLong("revision")));
            }
            return List.copyOf(folders);
        } catch (SQLException error) {
            throw new IOException("Unable to list remote folders", error);
        }
    }

    @Override
    public synchronized Optional<ViewerSyncRecord> find(String slideId) throws IOException {
        try (var statement = connection.prepareStatement("SELECT * FROM viewer_sync_records WHERE slide_id=?")) {
            statement.setString(1, slideId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readRecord(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IOException("Unable to read remote slide", error);
        }
    }

    @Override
    public synchronized List<ViewerSyncRecord> all() throws IOException {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM viewer_sync_records ORDER BY display_name,slide_id");
                var rows = statement.executeQuery()) {
            var records = new ArrayList<ViewerSyncRecord>();
            while (rows.next()) records.add(readRecord(rows));
            return List.copyOf(records);
        } catch (SQLException error) {
            throw new IOException("Unable to list remote slides", error);
        }
    }

    @Override
    public synchronized void markDirty(String slideId, Set<String> fields) throws IOException {
        var merged = new LinkedHashSet<>(find(slideId).orElseThrow().dirtyFields());
        merged.addAll(fields);
        update(slideId, "dirty_fields", encodeFields(merged));
    }

    @Override
    public synchronized void clearDirty(String slideId, Set<String> fields) throws IOException {
        var remaining = new LinkedHashSet<>(find(slideId).orElseThrow().dirtyFields());
        remaining.removeAll(fields);
        update(slideId, "dirty_fields", encodeFields(remaining));
    }

    @Override
    public synchronized void beginDownload(String slideId, Path partialPath, long bytes, String sha256)
            throws IOException {
        if (bytes < 0) throw new IllegalArgumentException("Download size must be non-negative");
        try (var statement = connection.prepareStatement("""
                UPDATE viewer_sync_records SET partial_path=?,download_bytes=?,download_offset=0,
                download_sha256=? WHERE slide_id=?
                """)) {
            statement.setString(1, partialPath.toAbsolutePath().normalize().toString());
            statement.setLong(2, bytes);
            statement.setString(3, sha256);
            statement.setString(4, slideId);
            requireUpdated(statement.executeUpdate(), slideId);
        } catch (SQLException error) {
            throw new IOException("Unable to start offline download", error);
        }
    }

    @Override
    public synchronized void advanceDownload(String slideId, long offset) throws IOException {
        var current = find(slideId).orElseThrow();
        if (offset < 0 || offset > current.downloadBytes()) {
            throw new IllegalArgumentException("Download offset is outside the artifact");
        }
        update(slideId, "download_offset", offset);
    }

    @Override
    public synchronized void saveCursor(long cursor) throws IOException {
        if (cursor < 0) throw new IllegalArgumentException("Cursor must be non-negative");
        try (var statement = connection.prepareStatement("UPDATE viewer_sync_state SET cursor=? WHERE singleton=1")) {
            statement.setLong(1, cursor);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to save Viewer cursor", error);
        }
    }

    @Override
    public synchronized long cursor() throws IOException {
        try (var statement = connection.prepareStatement("SELECT cursor FROM viewer_sync_state WHERE singleton=1");
                var rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (SQLException error) {
            throw new IOException("Unable to read Viewer cursor", error);
        }
    }

    @Override
    public synchronized void recordConflict(ViewerSyncConflict conflict) throws IOException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO viewer_sync_conflicts VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(slide_id,field) DO UPDATE SET local_value=excluded.local_value,
                  remote_value=excluded.remote_value,remote_revision=excluded.remote_revision,
                  detected_at=excluded.detected_at,unresolved=excluded.unresolved
                """)) {
            statement.setString(1, conflict.slideId());
            statement.setString(2, conflict.field());
            statement.setString(3, conflict.localValue());
            statement.setString(4, conflict.remoteValue());
            statement.setLong(5, conflict.remoteRevision());
            statement.setString(6, conflict.detectedAt().toString());
            statement.setInt(7, conflict.unresolved() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to save Viewer sync conflict", error);
        }
    }

    @Override
    public synchronized List<ViewerSyncConflict> conflicts() throws IOException {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM viewer_sync_conflicts WHERE unresolved=1 ORDER BY detected_at");
                var rows = statement.executeQuery()) {
            var conflicts = new ArrayList<ViewerSyncConflict>();
            while (rows.next()) {
                conflicts.add(new ViewerSyncConflict(rows.getString("slide_id"), rows.getString("field"),
                        rows.getString("local_value"), rows.getString("remote_value"),
                        rows.getLong("remote_revision"), Instant.parse(rows.getString("detected_at")),
                        rows.getInt("unresolved") != 0));
            }
            return List.copyOf(conflicts);
        } catch (SQLException error) {
            throw new IOException("Unable to list Viewer sync conflicts", error);
        }
    }

    private void update(String slideId, String column, Object value) throws IOException {
        if (!Set.of("dirty_fields", "download_offset").contains(column)) throw new IllegalArgumentException();
        try (var statement = connection.prepareStatement(
                "UPDATE viewer_sync_records SET " + column + "=? WHERE slide_id=?")) {
            statement.setObject(1, value);
            statement.setString(2, slideId);
            requireUpdated(statement.executeUpdate(), slideId);
        } catch (SQLException error) {
            throw new IOException("Unable to update Viewer sync state", error);
        }
    }

    private static void requireUpdated(int count, String slideId) throws IOException {
        if (count != 1) throw new IOException("Unknown remote slide: " + slideId);
    }

    private static ViewerSyncRecord readRecord(ResultSet row) throws SQLException {
        Map<String, Object> metadata;
        try {
            metadata = JSON.readValue(row.getString("metadata_json"), new TypeReference<>() { });
        } catch (IOException error) {
            throw new SQLException("Stored Viewer metadata is invalid", error);
        }
        var remote = new ViewerRemoteSlide(row.getString("slide_id"), row.getString("display_name"),
                row.getString("folder_id"), row.getString("status"), row.getLong("content_revision"),
                row.getLong("annotation_revision"), row.getLong("metadata_revision"),
                row.getLong("folder_revision"), row.getString("thumbnail_url"), row.getString("tile_url"),
                row.getLong("content_bytes"), row.getString("content_sha256"), metadata,
                Instant.parse(row.getString("remote_updated_at")));
        var path = row.getString("partial_path");
        return new ViewerSyncRecord(remote, decodeFields(row.getString("dirty_fields")),
                path.isBlank() ? Path.of("") : Path.of(path), row.getLong("download_bytes"),
                row.getLong("download_offset"), row.getString("download_sha256"));
    }

    private static String encodeFields(Set<String> fields) {
        return fields.stream().sorted().collect(Collectors.joining(","));
    }

    private static Set<String> decodeFields(String fields) {
        return fields.isBlank() ? Set.of() : Arrays.stream(fields.split(",")).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized void close() throws IOException {
        try { connection.close(); } catch (SQLException error) {
            throw new IOException("Unable to close Viewer sync store", error);
        }
    }
}
