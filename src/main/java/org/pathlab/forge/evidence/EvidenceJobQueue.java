package org.pathlab.forge.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Durable SQLite WAL queue with lane isolation, renewable leases, and in-place v1 migration. */
public final class EvidenceJobQueue implements AutoCloseable {
    private static final String ACTIVE_STATES = "'VALIDATING','RUNNING','REFINING','PACKAGING'";
    private static final long[] RETRY_DELAYS_SECONDS = {5, 30, 120};
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
                          id TEXT PRIMARY KEY, request_path TEXT NOT NULL, state TEXT NOT NULL,
                          stage TEXT NOT NULL, progress REAL NOT NULL, retry_count INTEGER NOT NULL,
                          cancel_requested INTEGER NOT NULL, lease_owner TEXT NOT NULL,
                          lease_expires_at TEXT NOT NULL, detail TEXT NOT NULL,
                          created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                        )""");
            }
            migrateV2();
        } catch (SQLException error) {
            throw new IOException("Unable to open Evidence Mentor queue", error);
        }
    }

    private void migrateV2() throws SQLException {
        addColumn("lane", "TEXT NOT NULL DEFAULT 'cpu_io'");
        addColumn("completed_units", "INTEGER NOT NULL DEFAULT 0");
        addColumn("total_units", "INTEGER NOT NULL DEFAULT 0");
        addColumn("checkpoint_path", "TEXT NOT NULL DEFAULT ''");
        addColumn("checkpoint_sha256", "TEXT NOT NULL DEFAULT ''");
        addColumn("last_heartbeat", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'");
        addColumn("throughput", "REAL NOT NULL DEFAULT 0");
        addColumn("eta_seconds", "INTEGER");
        addColumn("checkpoint_count", "INTEGER NOT NULL DEFAULT 0");
        addColumn("next_retry_at", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'");
        addColumn("failure_class", "TEXT NOT NULL DEFAULT ''");
        addColumn("failure_code", "TEXT NOT NULL DEFAULT ''");
        addColumn("request_sha256", "TEXT NOT NULL DEFAULT ''");
        addColumn("pack_sha256", "TEXT NOT NULL DEFAULT ''");
        addColumn("final_artifact_sha256", "TEXT NOT NULL DEFAULT ''");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS ix_evidence_jobs_lane_state ON evidence_jobs(lane,state,next_retry_at,created_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS runner_control (singleton INTEGER PRIMARY KEY CHECK(singleton=1),accepting_jobs INTEGER NOT NULL,updated_at TEXT NOT NULL)");
            statement.execute("INSERT OR IGNORE INTO runner_control VALUES(1,1,'1970-01-01T00:00:00Z')");
            statement.execute("CREATE TABLE IF NOT EXISTS evidence_progress_samples (job_id TEXT NOT NULL,sequence INTEGER PRIMARY KEY AUTOINCREMENT,completed_units INTEGER NOT NULL,recorded_at TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_evidence_progress_job_sequence ON evidence_progress_samples(job_id,sequence DESC)");
        }
    }

    private void addColumn(String name, String declaration) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(evidence_jobs)")) {
            while (rows.next()) if (name.equals(rows.getString("name"))) return;
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE evidence_jobs ADD COLUMN " + name + " " + declaration);
        }
    }

    public EvidenceJob submit(String id, Path requestPath, Instant now) throws IOException {
        return submit(id, requestPath, EvidenceExecutionLane.CPU_IO, now);
    }

    public synchronized EvidenceJob submit(String id, Path requestPath, EvidenceExecutionLane lane, Instant now) throws IOException {
        return submit(id, requestPath, lane, "", now);
    }

    public synchronized EvidenceJob submit(String id, Path requestPath, EvidenceExecutionLane lane,
            String packSha256, Instant now) throws IOException {
        var normalized = requestPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IllegalArgumentException("Evidence request file is unavailable");
        var job = new EvidenceJob(id, normalized, EvidenceJobState.QUEUED, "queued", 0, 0,
                false, "", Instant.EPOCH, "Queued for autonomous local analysis", now, now);
        try (var statement = connection.prepareStatement("""
                INSERT INTO evidence_jobs (id,request_path,state,stage,progress,retry_count,cancel_requested,
                  lease_owner,lease_expires_at,detail,created_at,updated_at,lane,request_sha256,pack_sha256,next_retry_at,last_heartbeat)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            bindBase(statement, job);
            statement.setString(13, lane.wire()); statement.setString(14, sha256(normalized));
            statement.setString(15, packSha256 == null ? "" : packSha256);
            statement.setString(16, Instant.EPOCH.toString()); statement.setString(17, Instant.EPOCH.toString());
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
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(read(rows)) : Optional.empty(); }
        } catch (SQLException error) { throw new IOException("Unable to read evidence job", error); }
    }

    public synchronized Optional<EvidenceJobSnapshot> snapshot(String id) throws IOException {
        try (var statement = connection.prepareStatement("SELECT * FROM evidence_jobs WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(readSnapshot(rows)) : Optional.empty(); }
        } catch (SQLException error) { throw new IOException("Unable to read evidence job snapshot", error); }
    }

    /** Makes leases from the previous runner boot immediately reclaimable without changing checkpoints. */
    public synchronized int recoverOrphanedActiveJobs(Instant now) throws IOException {
        var cancelled = 0;
        try (var statement = connection.prepareStatement("""
                UPDATE evidence_jobs SET state='CANCELLED',stage='cancelled',lease_owner='',lease_expires_at=?,
                  detail=?,updated_at=? WHERE cancel_requested=1
                  AND state IN ('VALIDATING','RUNNING','REFINING','PACKAGING')
                """)) {
            statement.setString(1, Instant.EPOCH.toString());
            statement.setString(2, "Cancelled during runner restart at the last durable checkpoint");
            statement.setString(3, now.toString());
            cancelled = statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to recover cancelled Evidence Mentor jobs", error);
        }
        try (var statement = connection.prepareStatement("""
                UPDATE evidence_jobs SET lease_owner='',lease_expires_at=?,last_heartbeat=?,
                  detail=?,updated_at=? WHERE cancel_requested=0
                  AND state IN ('VALIDATING','RUNNING','REFINING','PACKAGING')
                """)) {
            statement.setString(1, Instant.EPOCH.toString());
            statement.setString(2, now.toString());
            statement.setString(3, "Recovered after runner restart; verified checkpoint will be revalidated");
            statement.setString(4, now.toString());
            return cancelled + statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to recover orphaned Evidence Mentor leases", error);
        }
    }

    public synchronized List<EvidenceJobSnapshot> list(EvidenceJobState state, EvidenceExecutionLane lane, int limit) throws IOException {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Evidence job list limit is invalid");
        var sql = new StringBuilder("SELECT * FROM evidence_jobs WHERE 1=1");
        if (state != null) sql.append(" AND state=?");
        if (lane != null) sql.append(" AND lane=?");
        sql.append(" ORDER BY updated_at DESC,id LIMIT ?");
        try (var statement = connection.prepareStatement(sql.toString())) {
            var index = 1;
            if (state != null) statement.setString(index++, state.name());
            if (lane != null) statement.setString(index++, lane.wire());
            statement.setInt(index, limit);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<EvidenceJobSnapshot>();
                while (rows.next()) result.add(readSnapshot(rows));
                return List.copyOf(result);
            }
        } catch (SQLException error) { throw new IOException("Unable to list evidence jobs", error); }
    }

    public Optional<EvidenceJob> claimNext(String workerId, Instant now, Duration lease) throws IOException {
        return claimNext(null, workerId, now, lease);
    }

    public synchronized Optional<EvidenceJob> claimNext(EvidenceExecutionLane lane, String workerId, Instant now, Duration lease) throws IOException {
        validateWorker(workerId, lease);
        var accepting = acceptingJobs();
        try {
            try (var begin = connection.createStatement()) { begin.execute("BEGIN IMMEDIATE"); }
            EvidenceJob selected = null;
            var sql = "SELECT * FROM evidence_jobs WHERE cancel_requested=0 AND next_retry_at<=? AND ("
                    + (accepting ? "state='QUEUED' OR " : "") + "(state IN ("
                    + ACTIVE_STATES + ") AND lease_expires_at<=?))" + (lane == null ? "" : " AND lane=?")
                    + " ORDER BY created_at,id LIMIT 1";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, now.toString()); statement.setString(2, now.toString());
                if (lane != null) statement.setString(3, lane.wire());
                try (var rows = statement.executeQuery()) { if (rows.next()) selected = read(rows); }
            }
            if (selected == null) { commit(); return Optional.empty(); }
            var state = selected.state() == EvidenceJobState.QUEUED ? EvidenceJobState.VALIDATING : selected.state();
            var claimed = new EvidenceJob(selected.id(), selected.requestPath(), state,
                    selected.state() == EvidenceJobState.QUEUED ? "validating" : selected.stage(), selected.progress(),
                    selected.retryCount(), false, workerId, now.plus(lease),
                    selected.state() == EvidenceJobState.QUEUED ? "Validating immutable request and pack" : "Recovered after expired worker lease",
                    selected.createdAt(), now);
            update(claimed);
            try (var heartbeat = connection.prepareStatement("UPDATE evidence_jobs SET last_heartbeat=? WHERE id=?")) {
                heartbeat.setString(1, now.toString()); heartbeat.setString(2, selected.id()); heartbeat.executeUpdate();
            }
            commit(); return Optional.of(claimed);
        } catch (SQLException error) { rollbackQuietly(); throw new IOException("Unable to claim evidence job", error); }
    }

    public synchronized EvidenceJob heartbeat(String id, String workerId, long completed, long total,
            Path checkpoint, String checkpointSha256, Instant now, Duration lease) throws IOException {
        var current = requireOwned(id, workerId, now);
        if (completed < 0 || total < 0 || completed > total) throw new IllegalArgumentException("Worker progress counts are invalid");
        var sha = checkpointSha256 == null ? "" : checkpointSha256;
        if (!sha.isEmpty() && !sha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Checkpoint checksum is invalid");
        var prior = snapshot(id).orElseThrow();
        if (completed < prior.completedUnits() || total < prior.totalUnits()) throw new IllegalArgumentException("Worker progress is not monotonic");
        var delta = completed - prior.completedUnits();
        var progressRate = delta > 0 ? recordProgressRate(id, completed, total, now)
                : new ProgressRate(prior.throughput(), prior.etaSeconds());
        try (var statement = connection.prepareStatement("""
                UPDATE evidence_jobs SET completed_units=?,total_units=?,checkpoint_path=?,checkpoint_sha256=?,
                  last_heartbeat=?,throughput=?,eta_seconds=?,checkpoint_count=checkpoint_count+?,
                  lease_expires_at=?,updated_at=? WHERE id=?
                """)) {
            statement.setLong(1, completed); statement.setLong(2, total);
            statement.setString(3, checkpoint == null ? "" : checkpoint.toAbsolutePath().normalize().toString());
            statement.setString(4, sha); statement.setString(5, now.toString()); statement.setDouble(6, progressRate.throughput());
            if (progressRate.etaSeconds() == null) statement.setNull(7, java.sql.Types.INTEGER); else statement.setLong(7, progressRate.etaSeconds());
            statement.setInt(8, delta > 0 ? 1 : 0); statement.setString(9, now.plus(lease).toString());
            statement.setString(10, now.toString()); statement.setString(11, id); statement.executeUpdate();
        } catch (SQLException error) { throw new IOException("Unable to renew evidence lease", error); }
        return find(current.id()).orElseThrow();
    }

    private ProgressRate recordProgressRate(String id, long completed, long total, Instant now) throws IOException {
        try (var insert = connection.prepareStatement("INSERT INTO evidence_progress_samples(job_id,completed_units,recorded_at) VALUES(?,?,?)")) {
            insert.setString(1, id); insert.setLong(2, completed); insert.setString(3, now.toString()); insert.executeUpdate();
        } catch (SQLException error) { throw new IOException("Unable to record evidence throughput sample", error); }
        try (var trim = connection.prepareStatement("DELETE FROM evidence_progress_samples WHERE job_id=? AND sequence NOT IN (SELECT sequence FROM evidence_progress_samples WHERE job_id=? ORDER BY sequence DESC LIMIT 5)")) {
            trim.setString(1, id); trim.setString(2, id); trim.executeUpdate();
        } catch (SQLException error) { throw new IOException("Unable to bound evidence throughput samples", error); }
        try (var statement = connection.prepareStatement("SELECT completed_units,recorded_at FROM evidence_progress_samples WHERE job_id=? ORDER BY sequence")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                long firstUnits = 0, lastUnits = 0; Instant firstAt = null, lastAt = null; int count = 0;
                while (rows.next()) {
                    if (count++ == 0) { firstUnits = rows.getLong(1); firstAt = Instant.parse(rows.getString(2)); }
                    lastUnits = rows.getLong(1); lastAt = Instant.parse(rows.getString(2));
                }
                if (count < 2 || lastUnits <= firstUnits) return new ProgressRate(0, null);
                var seconds = Math.max(.001, Duration.between(firstAt, lastAt).toMillis() / 1000.0);
                var rate = (lastUnits - firstUnits) / seconds;
                return new ProgressRate(rate, Math.max(0, Math.round((total - completed) / rate)));
            }
        } catch (SQLException error) { throw new IOException("Unable to calculate evidence throughput", error); }
    }

    private record ProgressRate(double throughput, Long etaSeconds) { }

    public synchronized EvidenceJob checkpoint(String id, String workerId, EvidenceJobState state, String stage,
            double progress, String detail, Instant now, Duration lease) throws IOException {
        var current = requireOwned(id, workerId, now); requireTransition(current.state(), state);
        var updated = new EvidenceJob(id, current.requestPath(), state, stage, progress, current.retryCount(),
                current.cancelRequested(), state.terminal() ? "" : workerId, state.terminal() ? Instant.EPOCH : now.plus(lease),
                bounded(detail), current.createdAt(), now);
        updateChecked(updated); return updated;
    }

    public synchronized EvidenceJob terminate(String id, String workerId, EvidenceJobState state,
            String failureClass, String failureCode, String detail, Instant now, Duration lease) throws IOException {
        if (!state.terminal()) throw new IllegalArgumentException("Terminal evidence state is required");
        var terminal = checkpoint(id, workerId, state, state.name().toLowerCase(),
                find(id).orElseThrow().progress(), detail, now, lease);
        executeUpdate("UPDATE evidence_jobs SET failure_class=?,failure_code=? WHERE id=?",
                boundedCode(failureClass), boundedCode(failureCode), id);
        return terminal;
    }

    public synchronized void recordFinalArtifact(String id, String workerId, String sha256, Instant now) throws IOException {
        requireOwned(id, workerId, now);
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Final artifact checksum is invalid");
        executeUpdate("UPDATE evidence_jobs SET final_artifact_sha256=?,updated_at=? WHERE id=?", sha256, now.toString(), id);
    }

    public synchronized void requestCancel(String id, Instant now) throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Evidence job was not found"));
        if (current.state().terminal()) return;
        if (current.state() == EvidenceJobState.QUEUED) {
            executeUpdate("UPDATE evidence_jobs SET state='CANCELLED',stage='cancelled',cancel_requested=1,detail=?,updated_at=? WHERE id=?",
                    "Cancelled before execution by the local operator", now.toString(), id);
        } else {
            executeUpdate("UPDATE evidence_jobs SET cancel_requested=1,detail=?,updated_at=? WHERE id=?",
                    "Cancellation requested by the local operator", now.toString(), id);
        }
    }

    public synchronized EvidenceJob fail(String id, String workerId, String failureClass, String failureCode,
            String detail, boolean transientIo, Instant now) throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Evidence job was not found"));
        if (!workerId.equals(current.leaseOwner())) throw new IllegalStateException("Evidence job lease is not owned");
        var retries = transientIo ? current.retryCount() + 1 : current.retryCount();
        var retry = transientIo && retries <= RETRY_DELAYS_SECONDS.length;
        var next = retry ? now.plusSeconds(RETRY_DELAYS_SECONDS[retries - 1]) : Instant.EPOCH;
        var state = retry ? EvidenceJobState.QUEUED : EvidenceJobState.FAILED;
        try (var statement = connection.prepareStatement("""
                UPDATE evidence_jobs SET state=?,stage=?,retry_count=?,lease_owner='',lease_expires_at=?,next_retry_at=?,
                  failure_class=?,failure_code=?,detail=?,updated_at=? WHERE id=?
                """)) {
            statement.setString(1, state.name()); statement.setString(2, retry ? "retrying" : "failed");
            statement.setInt(3, Math.min(3, retries)); statement.setString(4, Instant.EPOCH.toString());
            statement.setString(5, next.toString()); statement.setString(6, boundedCode(failureClass));
            statement.setString(7, boundedCode(failureCode)); statement.setString(8, bounded(detail));
            statement.setString(9, now.toString()); statement.setString(10, id); statement.executeUpdate();
        } catch (SQLException error) { throw new IOException("Unable to record evidence failure", error); }
        return find(id).orElseThrow();
    }

    public EvidenceJob retryOrFail(String id, String workerId, String detail, Instant now) throws IOException {
        return fail(id, workerId, "transient_io", "IO_TRANSIENT", detail, true, now);
    }

    public synchronized EvidenceJob retry(String id, Instant now) throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Evidence job was not found"));
        if (current.state() != EvidenceJobState.FAILED && current.state() != EvidenceJobState.CANCELLED)
            throw new IllegalStateException("Only failed or cancelled jobs may be retried");
        var snapshot = snapshot(id).orElseThrow();
        var currentRequestSha = sha256(current.requestPath());
        if (!snapshot.requestSha256().isBlank() && !snapshot.requestSha256().equals(currentRequestSha))
            throw new IllegalStateException("Immutable job request changed; retry refused");
        try (var statement = connection.prepareStatement("""
                UPDATE evidence_jobs SET state='QUEUED',stage='queued',cancel_requested=0,lease_owner='',lease_expires_at=?,
                  next_retry_at=?,failure_class='',failure_code='',request_sha256=?,detail=?,updated_at=? WHERE id=?
                """)) {
            statement.setString(1, Instant.EPOCH.toString()); statement.setString(2, now.toString());
            statement.setString(3, currentRequestSha);
            statement.setString(4, "Requeued by local operator after immutable-input verification");
            statement.setString(5, now.toString()); statement.setString(6, id); statement.executeUpdate();
        } catch (SQLException error) { throw new IOException("Unable to retry evidence job", error); }
        return find(id).orElseThrow();
    }

    public synchronized boolean acceptingJobs() throws IOException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT accepting_jobs FROM runner_control WHERE singleton=1")) {
            return rows.next() && rows.getInt(1) != 0;
        } catch (SQLException error) { throw new IOException("Unable to read runner control", error); }
    }

    public synchronized void setAcceptingJobs(boolean accepting, Instant now) throws IOException {
        try (var statement = connection.prepareStatement("UPDATE runner_control SET accepting_jobs=?,updated_at=? WHERE singleton=1")) {
            statement.setInt(1, accepting ? 1 : 0); statement.setString(2, now.toString()); statement.executeUpdate();
        } catch (SQLException error) { throw new IOException("Unable to update runner control", error); }
    }

    private EvidenceJob requireOwned(String id, String workerId, Instant now) throws IOException {
        var current = find(id).orElseThrow(() -> new IllegalArgumentException("Evidence job was not found"));
        if (!workerId.equals(current.leaseOwner()) || current.leaseExpiresAt().isBefore(now)) throw new IllegalStateException("Evidence job lease is not owned by this worker");
        return current;
    }

    private void executeUpdate(String sql, String... values) throws IOException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            statement.executeUpdate();
        } catch (SQLException error) { throw new IOException("Unable to update evidence queue", error); }
    }

    private void updateChecked(EvidenceJob job) throws IOException { try { update(job); } catch (SQLException e) { throw new IOException("Unable to checkpoint evidence job", e); } }
    private void update(EvidenceJob job) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE evidence_jobs SET request_path=?,state=?,stage=?,progress=?,retry_count=?,cancel_requested=?,lease_owner=?,lease_expires_at=?,detail=?,created_at=?,updated_at=? WHERE id=?")) {
            statement.setString(1, job.requestPath().toString()); statement.setString(2, job.state().name()); statement.setString(3, job.stage());
            statement.setDouble(4, job.progress()); statement.setInt(5, job.retryCount()); statement.setInt(6, job.cancelRequested() ? 1 : 0);
            statement.setString(7, job.leaseOwner()); statement.setString(8, job.leaseExpiresAt().toString()); statement.setString(9, job.detail());
            statement.setString(10, job.createdAt().toString()); statement.setString(11, job.updatedAt().toString()); statement.setString(12, job.id());
            if (statement.executeUpdate() != 1) throw new SQLException("Evidence job was not updated");
        }
    }

    private static void bindBase(java.sql.PreparedStatement s, EvidenceJob j) throws SQLException {
        s.setString(1,j.id()); s.setString(2,j.requestPath().toString()); s.setString(3,j.state().name()); s.setString(4,j.stage());
        s.setDouble(5,j.progress()); s.setInt(6,j.retryCount()); s.setInt(7,j.cancelRequested()?1:0); s.setString(8,j.leaseOwner());
        s.setString(9,j.leaseExpiresAt().toString()); s.setString(10,j.detail()); s.setString(11,j.createdAt().toString()); s.setString(12,j.updatedAt().toString());
    }

    private static EvidenceJob read(ResultSet r) throws SQLException {
        return new EvidenceJob(r.getString("id"),Path.of(r.getString("request_path")),EvidenceJobState.valueOf(r.getString("state")),r.getString("stage"),
                r.getDouble("progress"),r.getInt("retry_count"),r.getInt("cancel_requested")!=0,r.getString("lease_owner"),
                Instant.parse(r.getString("lease_expires_at")),r.getString("detail"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));
    }

    private static EvidenceJobSnapshot readSnapshot(ResultSet r) throws SQLException {
        var checkpoint=r.getString("checkpoint_path"); var eta=r.getObject("eta_seconds");
        return new EvidenceJobSnapshot(r.getString("id"),EvidenceJobState.valueOf(r.getString("state")),r.getString("stage"),r.getDouble("progress"),
                EvidenceExecutionLane.fromWire(r.getString("lane")),r.getLong("completed_units"),r.getLong("total_units"),checkpoint.isBlank()?null:Path.of(checkpoint),
                r.getString("checkpoint_sha256"),Instant.parse(r.getString("last_heartbeat")),r.getDouble("throughput"),eta==null?null:r.getLong("eta_seconds"),
                Instant.parse(r.getString("next_retry_at")),r.getInt("retry_count"),r.getInt("cancel_requested")!=0,r.getString("lease_owner"),
                Instant.parse(r.getString("lease_expires_at")),r.getString("failure_class"),r.getString("failure_code"),r.getString("request_sha256"),
                r.getString("pack_sha256"),r.getString("final_artifact_sha256"),r.getString("detail"),
                Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));
    }

    private static void validateWorker(String id, Duration lease) {
        if (id==null||!id.matches("[A-Za-z0-9._-]{1,120}")) throw new IllegalArgumentException("Evidence worker id is invalid");
        if (lease.isNegative()||lease.isZero()||lease.compareTo(Duration.ofMinutes(10))>0) throw new IllegalArgumentException("Evidence lease is invalid");
    }
    private static void requireTransition(EvidenceJobState from,EvidenceJobState to) {
        if(from.terminal()) throw new IllegalStateException("Evidence job is already terminal");
        var allowed=switch(from){
            case QUEUED->java.util.Set.of(EvidenceJobState.VALIDATING,EvidenceJobState.CANCELLED);
            case VALIDATING->java.util.Set.of(EvidenceJobState.RUNNING,EvidenceJobState.ABSTAINED,EvidenceJobState.UNSUPPORTED,EvidenceJobState.FAILED,EvidenceJobState.CANCELLED);
            case RUNNING->java.util.Set.of(EvidenceJobState.REFINING,EvidenceJobState.PACKAGING,EvidenceJobState.ABSTAINED,EvidenceJobState.UNSUPPORTED,EvidenceJobState.FAILED,EvidenceJobState.CANCELLED);
            case REFINING->java.util.Set.of(EvidenceJobState.PACKAGING,EvidenceJobState.ABSTAINED,EvidenceJobState.FAILED,EvidenceJobState.CANCELLED);
            case PACKAGING->java.util.Set.of(EvidenceJobState.COMPLETED,EvidenceJobState.ABSTAINED,EvidenceJobState.FAILED,EvidenceJobState.CANCELLED);
            default->java.util.Set.of();};
        if(!allowed.contains(to)&&from!=to) throw new IllegalStateException("Invalid evidence job transition: "+from+" -> "+to);
    }
    private static String bounded(String v){if(v==null||v.isBlank())return "No additional detail";return v.length()<=500?v:v.substring(0,500);}
    private static String boundedCode(String v){return v!=null&&v.matches("[A-Za-z0-9._-]{1,80}")?v:"UNCLASSIFIED";}
    private static String sha256(Path path)throws IOException{try(InputStream in=Files.newInputStream(path)){var d=MessageDigest.getInstance("SHA-256");var b=new byte[1024*1024];int n;while((n=in.read(b))>=0)d.update(b,0,n);return HexFormat.of().formatHex(d.digest());}catch(java.security.GeneralSecurityException e){throw new IOException("SHA-256 unavailable",e);}}
    private void rollbackQuietly(){try(var s=connection.createStatement()){s.execute("ROLLBACK");}catch(SQLException ignored){}}
    private void commit()throws SQLException{try(var s=connection.createStatement()){s.execute("COMMIT");}}
    @Override public synchronized void close()throws IOException{try{connection.close();}catch(SQLException e){throw new IOException("Unable to close Evidence Mentor queue",e);}}
}
