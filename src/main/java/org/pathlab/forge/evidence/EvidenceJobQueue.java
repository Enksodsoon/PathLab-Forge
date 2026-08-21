package org.pathlab.forge.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Durable WAL queue with expiring leases and restart-safe checkpoints. */
public final class EvidenceJobQueue implements AutoCloseable {
    private static final String ACTIVE_STATES = "'VALIDATING','RUNNING','REFINING','PACKAGING'";
    private final Connection connection;

    public EvidenceJobQueue(Path database) throws IOException {
        try {
            var normalized = database.toAbsolutePath().normalize();
            Files.createDirectories(normalized.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS evidence_jobs (
                          id TEXT PRIMARY KEY,
                          request_path TEXT NOT NULL,
                          state TEXT NOT NULL,
                          stage TEXT NOT NULL,
                          progress REAL NOT NULL,
                          retry_count INTEGER NOT NULL,
                          cancel_requested INTEGER NOT NULL,
                          lease_owner TEXT NOT NULL,
                          lease_expires_at TEXT NOT NULL,
                          detail TEXT NOT NULL,
                          created_at TEXT NOT NULL,
                          updated_at TEXT NOT NULL
                        )""");
                statement.execute("CREATE INDEX IF NOT EXISTS ix_evidence_jobs_state_updated ON evidence_jobs(state, updated_at)");
            }
        } catch (SQLException error) {
            throw new IOException("Unable to open Evidence Mentor queue", error);
        }
    }

    public synchronized EvidenceJob submit(String id, Path requestPath, Instant now) throws IOException {
        var normalized = requestPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IllegalArgumentException("Evidence request file is unavailable");
        var job = new EvidenceJob(id, normalized, EvidenceJobState.QUEUED, "queued", 0, 0,
                false, "", Instant.EPOCH, "Queued for autonomous local analysis", now, now);
        try (var statement = connection.prepareStatement("""
                INSERT INTO evidence_jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            bind(statement, job);
            statement.executeUpdate();
            return job;
        } catch (SQLException error) {
            if (error.getErrorCode() == 19) throw new IllegalArgumentException("Evidence job already exists", error);
            throw new IOException("Unable to queue evidence job", error);
        }
    }

    public synchronized Optional<EvidenceJob> find(String id) throws IOException {
        try (var statement = connection.prepareStatement("SELECT * FROM evidence_jobs WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new IOException("Unable to read evidence job", error);
        }
    }

    public synchronized Optional<EvidenceJob> claimNext(
            String workerId, Instant now, Duration lease) throws IOException {
        if (workerId == null || !workerId.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("Evidence worker id is invalid");
        }
        if (lease.isNegative() || lease.isZero() || lease.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Evidence lease is invalid");
        }
        try {
            try (var begin = connection.createStatement()) {
                begin.execute("BEGIN IMMEDIATE");
            }
            EvidenceJob selected = null;
            var claimSql = "SELECT * FROM evidence_jobs "
                    + "WHERE cancel_requested=0 AND (state='QUEUED' OR (state IN ("
                    + ACTIVE_STATES + ") AND lease_expires_at<=?)) "
                    + "ORDER BY created_at, id LIMIT 1";
            try (var statement = connection.prepareStatement(claimSql)) {
                statement.setString(1, now.toString());
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) selected = read(rows);
                }
            }
            if (selected == null) {
                commit();
                return Optional.empty();
            }
            var state = selected.state() == EvidenceJobState.QUEUED
                    ? EvidenceJobState.VALIDATING : selected.state();
            var claimed = new EvidenceJob(selected.id(), selected.requestPath(), state,
                    selected.state() == EvidenceJobState.QUEUED ? "validating" : selected.stage(),
                    selected.progress(), selected.retryCount(), false, workerId, now.plus(lease),
                    selected.state() == EvidenceJobState.QUEUED
                            ? "Validating immutable request and pack" : "Recovered after expired worker lease",
                    selected.createdAt(), now);
            update(claimed);
            commit();
            return Optional.of(claimed);
        } catch (SQLException error) {
            rollbackQuietly();
            throw new IOException("Unable to claim evidence job", error);
        }
    }

    public synchronized EvidenceJob checkpoint(
            String id, String workerId, EvidenceJobState state, String stage,
            double progress, String detail, Instant now, Duration lease) throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Evidence job was not found"));
        if (!workerId.equals(current.leaseOwner()) || current.leaseExpiresAt().isBefore(now)) {
            throw new IllegalStateException("Evidence job lease is not owned by this worker");
        }
        requireTransition(current.state(), state);
        var updated = new EvidenceJob(id, current.requestPath(), state, stage, progress,
                current.retryCount(), current.cancelRequested(), state.terminal() ? "" : workerId,
                state.terminal() ? Instant.EPOCH : now.plus(lease), bounded(detail), current.createdAt(), now);
        updateChecked(updated);
        return updated;
    }

    public synchronized void requestCancel(String id, Instant now) throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Evidence job was not found"));
        if (current.state().terminal()) return;
        try (var statement = connection.prepareStatement(
                "UPDATE evidence_jobs SET cancel_requested=1, detail=?, updated_at=? WHERE id=?")) {
            statement.setString(1, "Cancellation requested by the local operator");
            statement.setString(2, now.toString());
            statement.setString(3, id);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to request evidence cancellation", error);
        }
    }

    public synchronized EvidenceJob retryOrFail(String id, String workerId, String detail, Instant now)
            throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Evidence job was not found"));
        if (!workerId.equals(current.leaseOwner())) throw new IllegalStateException("Evidence job lease is not owned");
        var retries = current.retryCount() + 1;
        var state = retries <= 3 ? EvidenceJobState.QUEUED : EvidenceJobState.FAILED;
        var next = new EvidenceJob(id, current.requestPath(), state,
                state == EvidenceJobState.QUEUED ? "retrying" : "failed", current.progress(),
                Math.min(3, retries), false, "", Instant.EPOCH, bounded(detail), current.createdAt(), now);
        updateChecked(next);
        return next;
    }

    private void updateChecked(EvidenceJob job) throws IOException {
        try { update(job); } catch (SQLException error) { throw new IOException("Unable to checkpoint evidence job", error); }
    }

    private void update(EvidenceJob job) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE evidence_jobs SET request_path=?, state=?, stage=?, progress=?, retry_count=?,
                  cancel_requested=?, lease_owner=?, lease_expires_at=?, detail=?, created_at=?, updated_at=?
                WHERE id=?
                """)) {
            statement.setString(1, job.requestPath().toString());
            statement.setString(2, job.state().name());
            statement.setString(3, job.stage());
            statement.setDouble(4, job.progress());
            statement.setInt(5, job.retryCount());
            statement.setInt(6, job.cancelRequested() ? 1 : 0);
            statement.setString(7, job.leaseOwner());
            statement.setString(8, job.leaseExpiresAt().toString());
            statement.setString(9, job.detail());
            statement.setString(10, job.createdAt().toString());
            statement.setString(11, job.updatedAt().toString());
            statement.setString(12, job.id());
            if (statement.executeUpdate() != 1) throw new SQLException("Evidence job was not updated");
        }
    }

    private static void bind(java.sql.PreparedStatement statement, EvidenceJob job) throws SQLException {
        statement.setString(1, job.id());
        statement.setString(2, job.requestPath().toString());
        statement.setString(3, job.state().name());
        statement.setString(4, job.stage());
        statement.setDouble(5, job.progress());
        statement.setInt(6, job.retryCount());
        statement.setInt(7, job.cancelRequested() ? 1 : 0);
        statement.setString(8, job.leaseOwner());
        statement.setString(9, job.leaseExpiresAt().toString());
        statement.setString(10, job.detail());
        statement.setString(11, job.createdAt().toString());
        statement.setString(12, job.updatedAt().toString());
    }

    private static EvidenceJob read(ResultSet row) throws SQLException {
        return new EvidenceJob(row.getString("id"), Path.of(row.getString("request_path")),
                EvidenceJobState.valueOf(row.getString("state")), row.getString("stage"),
                row.getDouble("progress"), row.getInt("retry_count"),
                row.getInt("cancel_requested") != 0, row.getString("lease_owner"),
                Instant.parse(row.getString("lease_expires_at")), row.getString("detail"),
                Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")));
    }

    private static void requireTransition(EvidenceJobState from, EvidenceJobState to) {
        if (from.terminal()) throw new IllegalStateException("Evidence job is already terminal");
        var allowed = switch (from) {
            case QUEUED -> java.util.Set.of(EvidenceJobState.VALIDATING, EvidenceJobState.CANCELLED);
            case VALIDATING -> java.util.Set.of(EvidenceJobState.RUNNING, EvidenceJobState.ABSTAINED,
                    EvidenceJobState.UNSUPPORTED, EvidenceJobState.FAILED, EvidenceJobState.CANCELLED);
            case RUNNING -> java.util.Set.of(EvidenceJobState.REFINING, EvidenceJobState.PACKAGING,
                    EvidenceJobState.ABSTAINED, EvidenceJobState.UNSUPPORTED, EvidenceJobState.FAILED,
                    EvidenceJobState.CANCELLED);
            case REFINING -> java.util.Set.of(EvidenceJobState.PACKAGING, EvidenceJobState.ABSTAINED,
                    EvidenceJobState.FAILED, EvidenceJobState.CANCELLED);
            case PACKAGING -> java.util.Set.of(EvidenceJobState.COMPLETED, EvidenceJobState.ABSTAINED,
                    EvidenceJobState.FAILED, EvidenceJobState.CANCELLED);
            default -> java.util.Set.of();
        };
        if (!allowed.contains(to) && from != to) {
            throw new IllegalStateException("Invalid evidence job transition: " + from + " -> " + to);
        }
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "No additional detail";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private void rollbackQuietly() {
        try (var statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException ignored) { }
    }

    private void commit() throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("COMMIT");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try { connection.close(); } catch (SQLException error) { throw new IOException("Unable to close Evidence Mentor queue", error); }
    }
}
