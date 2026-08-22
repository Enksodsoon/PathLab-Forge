package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Durable parent coordinator. It schedules children but never consumes an execution lane. */
public final class QualificationRunStore implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection connection;
    private final Path stateRoot;

    public QualificationRunStore(Path database, Path stateRoot) throws IOException {
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize());
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS qualification_runs (
                          id TEXT PRIMARY KEY, manifest_path TEXT NOT NULL, manifest_sha256 TEXT NOT NULL,
                          state TEXT NOT NULL, cancel_requested INTEGER NOT NULL,
                          campaign_completed INTEGER NOT NULL, campaign_target_met INTEGER NOT NULL,
                          created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                        )""");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS qualification_run_tracks (
                          run_id TEXT NOT NULL, track_id TEXT NOT NULL, candidate_id TEXT NOT NULL,
                          capability TEXT NOT NULL, scope TEXT NOT NULL, request_path TEXT NOT NULL,
                          remediation_request_path TEXT NOT NULL, expected_attestation_path TEXT NOT NULL,
                          protocol_sha256 TEXT NOT NULL, dependencies_json TEXT NOT NULL, required INTEGER NOT NULL,
                          state TEXT NOT NULL, verdict TEXT NOT NULL, attempt INTEGER NOT NULL, job_id TEXT NOT NULL,
                          artifact_sha256 TEXT NOT NULL, failure_code TEXT NOT NULL, detail TEXT NOT NULL,
                          updated_at TEXT NOT NULL, PRIMARY KEY(run_id,track_id),
                          FOREIGN KEY(run_id) REFERENCES qualification_runs(id)
                        )""");
                statement.execute("CREATE INDEX IF NOT EXISTS ix_qualification_tracks_run_state ON qualification_run_tracks(run_id,state,track_id)");
            }
        } catch (SQLException error) {
            throw new IOException("Unable to open qualification campaign store", error);
        }
    }

    public synchronized QualificationRunSnapshot create(QualificationCampaignManifest manifest, Instant now)
            throws IOException {
        try {
            begin();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO qualification_runs
                    (id,manifest_path,manifest_sha256,state,cancel_requested,campaign_completed,
                     campaign_target_met,created_at,updated_at) VALUES(?,?,?,?,0,0,0,?,?)
                    """)) {
                statement.setString(1, manifest.campaignId());
                statement.setString(2, manifest.path().toString());
                statement.setString(3, manifest.sha256());
                statement.setString(4, "running");
                statement.setString(5, now.toString());
                statement.setString(6, now.toString());
                statement.executeUpdate();
            }
            for (var track : manifest.tracks()) insertTrack(manifest.campaignId(), track, now);
            commit();
            return snapshot(manifest.campaignId()).orElseThrow();
        } catch (SQLException error) {
            rollbackQuietly();
            if (error.getErrorCode() == 19) throw new IllegalArgumentException("Qualification campaign already exists", error);
            throw new IOException("Unable to create qualification campaign", error);
        }
    }

    public synchronized Optional<QualificationRunSnapshot> snapshot(String id) throws IOException {
        try (var statement = connection.prepareStatement("SELECT * FROM qualification_runs WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(readRun(rows, tracks(id)));
            }
        } catch (SQLException error) {
            throw new IOException("Unable to read qualification campaign", error);
        }
    }

    public synchronized List<QualificationRunSnapshot> list(int limit) throws IOException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Qualification campaign limit is invalid");
        var result = new ArrayList<QualificationRunSnapshot>();
        try (var statement = connection.prepareStatement(
                "SELECT * FROM qualification_runs ORDER BY updated_at DESC,id LIMIT ?")) {
            statement.setInt(1, limit);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(readRun(rows, tracks(rows.getString("id"))));
            }
        } catch (SQLException error) {
            throw new IOException("Unable to list qualification campaigns", error);
        }
        return List.copyOf(result);
    }

    public synchronized void requestCancel(String id, EvidenceJobQueue queue, Instant now) throws IOException {
        var run = snapshot(id).orElseThrow(() -> new IllegalArgumentException("Qualification campaign was not found"));
        if (run.campaignCompleted()) return;
        execute("UPDATE qualification_runs SET cancel_requested=1,state='cancelling',updated_at=? WHERE id=?",
                now.toString(), id);
        for (var track : run.tracks()) {
            if (!track.jobId().isBlank()) {
                var job = queue.snapshot(track.jobId());
                if (job.isPresent() && !job.get().state().terminal()) queue.requestCancel(track.jobId(), now);
            }
            if (track.verdict().isBlank()) terminalTrack(id, track.id(), "not_evaluable",
                    "CAMPAIGN_CANCELLED", "Cancelled by the local operator", now);
        }
        finalizeRun(id, now);
    }

    public synchronized QualificationRunSnapshot resume(String id, Instant now) throws IOException {
        var run = snapshot(id).orElseThrow(() -> new IllegalArgumentException("Qualification campaign was not found"));
        if (run.campaignCompleted()) throw new IllegalStateException("Qualification campaign is already terminal");
        execute("UPDATE qualification_runs SET state='running',cancel_requested=0,updated_at=? WHERE id=?",
                now.toString(), id);
        execute("UPDATE qualification_run_tracks SET state='pending',updated_at=? WHERE run_id=? AND state='waiting_attestation'",
                now.toString(), id);
        return snapshot(id).orElseThrow();
    }

    public synchronized QualificationRunSnapshot attachAttestation(String id, String trackId, Path report,
            Instant now) throws IOException {
        var manifest = loadManifest(id);
        var track = manifest.tracks().stream().filter(item -> item.id().equals(trackId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Qualification track was not found"));
        var validated = new QualificationReportValidator(stateRoot).validate(report, manifest, track);
        if ("qualified".equals(validated.verdict()) && !"local-benchmark".equals(track.scope())) {
            var request = JSON.readTree(track.requestPath().toFile());
            var packPath = Path.of(request.path("packManifest").asText()).toAbsolutePath().normalize();
            var pack = EvidencePackManifest.load(packPath);
            if (!pack.sha256().equals(validated.packManifestSha256())) {
                throw new IllegalArgumentException("Qualification report pack checksum changed");
            }
            new QualifiedPackRegistry(stateRoot).register(pack, track.protocolSha256(),
                    validated.manifestSha256(), now);
        }
        terminalTrack(id, trackId, validated.verdict(), "",
                "Signed qualification attestation accepted", now, validated.manifestSha256());
        finalizeRun(id, now);
        return snapshot(id).orElseThrow();
    }

    /** Advances every parent deterministically; active scientific work remains in child jobs. */
    public synchronized void tick(EvidenceJobQueue queue, Instant now) throws IOException {
        for (var run : list(100)) {
            if (run.campaignCompleted()) continue;
            QualificationCampaignManifest manifest;
            try {
                manifest = loadManifest(run.id());
            } catch (IllegalArgumentException error) {
                failRemaining(run.id(), "CAMPAIGN_MANIFEST_CHANGED", error.getMessage(), now);
                finalizeRun(run.id(), now);
                continue;
            }
            for (var track : snapshot(run.id()).orElseThrow().tracks()) {
                if (!track.verdict().isBlank()) continue;
                var declared = manifest.tracks().stream().filter(item -> item.id().equals(track.id())).findFirst().orElseThrow();
                if ("pending".equals(track.state())) {
                    var dependencyVerdicts = dependencyVerdicts(run.id(), declared.dependsOn());
                    if (dependencyVerdicts.stream().anyMatch(value -> !value.isBlank() && !"qualified".equals(value))) {
                        terminalTrack(run.id(), track.id(), "not_evaluable", "DEPENDENCY_NOT_QUALIFIED",
                                "A frozen dependency did not qualify", now);
                    } else if (dependencyVerdicts.stream().allMatch("qualified"::equals)) {
                        submitChild(run.id(), declared, track.attempt(), queue, now);
                    }
                    continue;
                }
                if ("waiting_attestation".equals(track.state())) {
                    if (declared.expectedAttestationPath() != null && Files.isRegularFile(declared.expectedAttestationPath())) {
                        try {
                            attachAttestation(run.id(), track.id(), declared.expectedAttestationPath(), now);
                        } catch (IllegalArgumentException invalid) {
                            remediateOrFinish(run.id(), declared, track, "ATTESTATION_INVALID", invalid.getMessage(), now);
                        }
                    }
                    continue;
                }
                if (!track.jobId().isBlank()) {
                    var job = queue.snapshot(track.jobId());
                    if (job.isPresent() && job.get().state().terminal()) {
                        handleTerminalJob(run.id(), declared, track, job.get(), now);
                    }
                }
            }
            finalizeRun(run.id(), now);
        }
    }

    private void handleTerminalJob(String runId, QualificationCampaignManifest.Track declared,
            QualificationRunSnapshot.Track track, EvidenceJobSnapshot job, Instant now) throws IOException {
        switch (job.state()) {
            case COMPLETED -> {
                if (declared.expectedAttestationPath() != null && Files.isRegularFile(declared.expectedAttestationPath())) {
                    try {
                        attachAttestation(runId, track.id(), declared.expectedAttestationPath(), now);
                    } catch (IllegalArgumentException invalid) {
                        remediateOrFinish(runId, declared, track, "ATTESTATION_INVALID", invalid.getMessage(), now);
                    }
                } else {
                    updateTrack(runId, track.id(), "waiting_attestation", track.attempt(), track.jobId(), "",
                            "", "Child completed; awaiting a signed qualification attestation", now);
                    execute("UPDATE qualification_runs SET state='waiting_attestation',updated_at=? WHERE id=?",
                            now.toString(), runId);
                }
            }
            case UNSUPPORTED -> terminalTrack(runId, track.id(), "unsupported",
                    blankCode(job.failureCode(), "CAPABILITY_UNSUPPORTED"), job.detail(), now);
            case ABSTAINED -> terminalTrack(runId, track.id(), "not_evaluable",
                    blankCode(job.failureCode(), "CHILD_ABSTAINED"), job.detail(), now);
            case FAILED, CANCELLED -> remediateOrFinish(runId, declared, track,
                    blankCode(job.failureCode(), "CHILD_FAILED"), job.detail(), now);
            default -> { }
        }
    }

    private void remediateOrFinish(String runId, QualificationCampaignManifest.Track declared,
            QualificationRunSnapshot.Track current, String code, String detail, Instant now) throws IOException {
        if (current.attempt() == 0 && declared.remediationRequestPath() != null) {
            execute("""
                    UPDATE qualification_run_tracks SET request_path=?,state='pending',attempt=1,job_id='',
                    failure_code=?,detail=?,updated_at=? WHERE run_id=? AND track_id=?
                    """, declared.remediationRequestPath().toString(), code,
                    "Bounded remediation scheduled: " + bounded(detail), now.toString(), runId, current.id());
            execute("UPDATE qualification_runs SET state='remediating',updated_at=? WHERE id=?",
                    now.toString(), runId);
        } else {
            terminalTrack(runId, current.id(), "experimental", code, bounded(detail), now);
        }
    }

    private void submitChild(String runId, QualificationCampaignManifest.Track track, int attempt,
            EvidenceJobQueue queue, Instant now) throws IOException {
        var request = attempt == 0 ? track.requestPath() : track.remediationRequestPath();
        var jobId = childId(runId, track.id(), attempt);
        var plan = EvidenceJobProcessor.executionPlan(request, jobId, stateRoot);
        queue.submit(jobId, request, plan.lane(), plan.packSha256(), now);
        updateTrack(runId, track.id(), "running", attempt, jobId, "", "",
                "Child job accepted by the autonomous runner", now);
    }

    private QualificationCampaignManifest loadManifest(String id) throws IOException {
        try (var statement = connection.prepareStatement(
                "SELECT manifest_path,manifest_sha256 FROM qualification_runs WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Qualification campaign was not found");
                var manifest = QualificationCampaignManifest.load(Path.of(rows.getString(1)));
                if (!manifest.sha256().equals(rows.getString(2))) {
                    throw new IllegalArgumentException("Qualification campaign checksum changed");
                }
                return manifest;
            }
        } catch (SQLException error) {
            throw new IOException("Unable to load qualification campaign manifest", error);
        }
    }

    private List<String> dependencyVerdicts(String runId, List<String> dependencies) throws IOException {
        var result = new ArrayList<String>();
        for (var dependency : dependencies) {
            try (var statement = connection.prepareStatement(
                    "SELECT verdict FROM qualification_run_tracks WHERE run_id=? AND track_id=?")) {
                statement.setString(1, runId);
                statement.setString(2, dependency);
                try (var rows = statement.executeQuery()) {
                    result.add(rows.next() ? rows.getString(1) : "not_evaluable");
                }
            } catch (SQLException error) {
                throw new IOException("Unable to read qualification dependency", error);
            }
        }
        return result;
    }

    private void finalizeRun(String id, Instant now) throws IOException {
        var tracks = tracks(id);
        if (tracks.isEmpty() || tracks.stream().anyMatch(track -> track.verdict().isBlank())) return;
        var target = tracks.stream().allMatch(track -> "qualified".equals(track.verdict()));
        execute("""
                UPDATE qualification_runs SET state='completed',campaign_completed=1,
                campaign_target_met=?,updated_at=? WHERE id=?
                """, target ? "1" : "0", now.toString(), id);
        new CapabilityMatrixWriter(stateRoot).write(snapshot(id).orElseThrow(), now);
    }

    private void failRemaining(String id, String code, String detail, Instant now) throws IOException {
        execute("""
                UPDATE qualification_run_tracks SET state='terminal',verdict='not_evaluable',
                failure_code=?,detail=?,updated_at=? WHERE run_id=? AND verdict=''
                """, code, bounded(detail), now.toString(), id);
    }

    private void terminalTrack(String runId, String trackId, String verdict, String failureCode,
            String detail, Instant now) throws IOException {
        terminalTrack(runId, trackId, verdict, failureCode, detail, now, "");
    }

    private void terminalTrack(String runId, String trackId, String verdict, String failureCode,
            String detail, Instant now, String artifactSha) throws IOException {
        if (!java.util.Set.of("qualified", "experimental", "unsupported", "not_evaluable").contains(verdict)) {
            throw new IllegalArgumentException("Qualification terminal verdict is invalid");
        }
        execute("""
                UPDATE qualification_run_tracks SET state='terminal',verdict=?,artifact_sha256=?,
                failure_code=?,detail=?,updated_at=? WHERE run_id=? AND track_id=?
                """, verdict, artifactSha, failureCode, bounded(detail), now.toString(), runId, trackId);
    }

    private void updateTrack(String runId, String trackId, String state, int attempt, String jobId,
            String artifactSha, String failureCode, String detail, Instant now) throws IOException {
        execute("""
                UPDATE qualification_run_tracks SET state=?,attempt=?,job_id=?,artifact_sha256=?,
                failure_code=?,detail=?,updated_at=? WHERE run_id=? AND track_id=?
                """, state, Integer.toString(attempt), jobId, artifactSha, failureCode,
                bounded(detail), now.toString(), runId, trackId);
    }

    private void insertTrack(String runId, QualificationCampaignManifest.Track track, Instant now)
            throws SQLException, IOException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO qualification_run_tracks
                (run_id,track_id,candidate_id,capability,scope,request_path,remediation_request_path,
                 expected_attestation_path,protocol_sha256,dependencies_json,required,state,verdict,
                 attempt,job_id,artifact_sha256,failure_code,detail,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'pending','',0,'','','','Waiting for frozen dependencies',?)
                """)) {
            statement.setString(1, runId);
            statement.setString(2, track.id());
            statement.setString(3, track.candidateId());
            statement.setString(4, track.capability());
            statement.setString(5, track.scope());
            statement.setString(6, track.requestPath().toString());
            statement.setString(7, track.remediationRequestPath() == null ? "" : track.remediationRequestPath().toString());
            statement.setString(8, track.expectedAttestationPath() == null ? "" : track.expectedAttestationPath().toString());
            statement.setString(9, track.protocolSha256());
            statement.setString(10, JSON.writeValueAsString(track.dependsOn()));
            statement.setInt(11, track.required() ? 1 : 0);
            statement.setString(12, now.toString());
            statement.executeUpdate();
        }
    }

    private List<QualificationRunSnapshot.Track> tracks(String id) throws IOException {
        var result = new ArrayList<QualificationRunSnapshot.Track>();
        try (var statement = connection.prepareStatement(
                "SELECT * FROM qualification_run_tracks WHERE run_id=? ORDER BY track_id")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(new QualificationRunSnapshot.Track(
                        rows.getString("track_id"), rows.getString("candidate_id"),
                        rows.getString("capability"), rows.getString("scope"), rows.getString("state"),
                        rows.getString("verdict"), rows.getInt("attempt"), rows.getString("job_id"),
                        rows.getString("artifact_sha256"), rows.getString("failure_code"),
                        rows.getString("detail"), Instant.parse(rows.getString("updated_at"))));
            }
        } catch (SQLException error) {
            throw new IOException("Unable to read qualification campaign tracks", error);
        }
        return List.copyOf(result);
    }

    private static QualificationRunSnapshot readRun(ResultSet rows,
            List<QualificationRunSnapshot.Track> tracks) throws SQLException {
        return new QualificationRunSnapshot(rows.getString("id"), rows.getString("manifest_sha256"),
                rows.getString("state"), rows.getInt("campaign_completed") != 0,
                rows.getInt("campaign_target_met") != 0, rows.getInt("cancel_requested") != 0,
                tracks, Instant.parse(rows.getString("created_at")), Instant.parse(rows.getString("updated_at")));
    }

    private static String childId(String runId, String trackId, int attempt) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256")
                    .digest((runId + "\n" + trackId + "\n" + attempt).getBytes(StandardCharsets.UTF_8));
            return "qualification-" + HexFormat.of().formatHex(bytes).substring(0, 32);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void execute(String sql, String... values) throws IOException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < values.length; index++) statement.setString(index + 1, values[index]);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Unable to update qualification campaign", error);
        }
    }

    private void begin() throws SQLException { try (var statement = connection.createStatement()) { statement.execute("BEGIN IMMEDIATE"); } }
    private void commit() throws SQLException { try (var statement = connection.createStatement()) { statement.execute("COMMIT"); } }
    private void rollbackQuietly() { try (var statement = connection.createStatement()) { statement.execute("ROLLBACK"); } catch (SQLException ignored) { } }
    private static String blankCode(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String bounded(String value) { if (value == null || value.isBlank()) return "No additional detail"; return value.length() <= 500 ? value : value.substring(0, 500); }

    @Override public synchronized void close() throws IOException {
        try { connection.close(); } catch (SQLException error) { throw new IOException("Unable to close qualification campaign store", error); }
    }
}
