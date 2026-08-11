package org.pathlab.forge.viewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SqliteViewerDeliveryStore implements ViewerDeliveryStore {
    private final Connection connection;

    public SqliteViewerDeliveryStore(Path database) throws IOException {
        try {
            var normalized = database.toAbsolutePath().normalize();
            Files.createDirectories(normalized.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS viewer_delivery_jobs (
                          id TEXT PRIMARY KEY,
                          dataset_id TEXT NOT NULL,
                          artifact_revision_id TEXT NOT NULL,
                          artifact_sha256 TEXT NOT NULL,
                          artifact_bytes INTEGER NOT NULL,
                          viewer_origin TEXT NOT NULL,
                          ingest_id TEXT NOT NULL,
                          upload_uri TEXT NOT NULL,
                          confirmed_offset INTEGER NOT NULL,
                          state TEXT NOT NULL,
                          remote_slide_id TEXT NOT NULL,
                          result_bundle_id TEXT NOT NULL,
                          result_sha256 TEXT NOT NULL,
                          result_bytes INTEGER NOT NULL,
                          first_failure_at TEXT NOT NULL,
                          next_retry_at TEXT NOT NULL,
                          retry_count INTEGER NOT NULL,
                          detail TEXT NOT NULL,
                          updated_at TEXT NOT NULL
                        )""");
            }
        } catch (SQLException error) {
            throw new IOException("Unable to open Viewer delivery store", error);
        }
    }

    @Override
    public synchronized void save(ViewerDeliveryJob job) throws IOException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO viewer_delivery_jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                  dataset_id=excluded.dataset_id,
                  artifact_revision_id=excluded.artifact_revision_id,
                  artifact_sha256=excluded.artifact_sha256,
                  artifact_bytes=excluded.artifact_bytes,
                  viewer_origin=excluded.viewer_origin,
                  ingest_id=excluded.ingest_id,
                  upload_uri=excluded.upload_uri,
                  confirmed_offset=excluded.confirmed_offset,
                  state=excluded.state,
                  remote_slide_id=excluded.remote_slide_id,
                  result_bundle_id=excluded.result_bundle_id,
                  result_sha256=excluded.result_sha256,
                  result_bytes=excluded.result_bytes,
                  first_failure_at=excluded.first_failure_at,
                  next_retry_at=excluded.next_retry_at,
                  retry_count=excluded.retry_count,
                  detail=excluded.detail,
                  updated_at=excluded.updated_at
                """)) {
            statement.setString(1, job.id());
            statement.setString(2, job.datasetId());
            statement.setString(3, job.artifactRevisionId());
            statement.setString(4, job.artifactSha256());
            statement.setLong(5, job.artifactBytes());
            statement.setString(6, job.viewerOrigin());
            statement.setString(7, job.ingestId());
            statement.setString(8, job.uploadUri());
            statement.setLong(9, job.confirmedOffset());
            statement.setString(10, job.state().name());
            statement.setString(11, job.remoteSlideId());
            statement.setString(12, job.resultBundleId());
            statement.setString(13, job.resultSha256());
            statement.setLong(14, job.resultBytes());
            statement.setString(15, job.firstFailureAt().toString());
            statement.setString(16, job.nextRetryAt().toString());
            statement.setInt(17, job.retryCount());
            statement.setString(18, job.detail());
            statement.setString(19, job.updatedAt().toString());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to persist Viewer delivery", error);
        }
    }

    @Override
    public synchronized Optional<ViewerDeliveryJob> find(String id) throws IOException {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM viewer_delivery_jobs WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IOException("Unable to read Viewer delivery", error);
        }
    }

    @Override
    public synchronized List<ViewerDeliveryJob> resumable() throws IOException {
        try (var statement = connection.prepareStatement("""
                SELECT * FROM viewer_delivery_jobs
                WHERE state IN ('QUEUED','UPLOADING_OME','VERIFYING_OME','IMAGE_READY','SYNCING_RESULTS','RETRYING')
                ORDER BY updated_at
                """); var rows = statement.executeQuery()) {
            var jobs = new ArrayList<ViewerDeliveryJob>();
            while (rows.next()) {
                jobs.add(read(rows));
            }
            return List.copyOf(jobs);
        } catch (SQLException error) {
            throw new IOException("Unable to list resumable Viewer deliveries", error);
        }
    }

    private static ViewerDeliveryJob read(ResultSet row) throws SQLException {
        return new ViewerDeliveryJob(
                row.getString("id"), row.getString("dataset_id"),
                row.getString("artifact_revision_id"), row.getString("artifact_sha256"),
                row.getLong("artifact_bytes"), row.getString("viewer_origin"),
                row.getString("ingest_id"), row.getString("upload_uri"),
                row.getLong("confirmed_offset"), ViewerDeliveryState.valueOf(row.getString("state")),
                row.getString("remote_slide_id"), row.getString("result_bundle_id"),
                row.getString("result_sha256"), row.getLong("result_bytes"),
                Instant.parse(row.getString("first_failure_at")),
                Instant.parse(row.getString("next_retry_at")), row.getInt("retry_count"),
                row.getString("detail"), Instant.parse(row.getString("updated_at")));
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException error) {
            throw new IOException("Unable to close Viewer delivery store", error);
        }
    }
}
