package org.pathlab.forge.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AnalysisRunStore {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED");
    private final String url;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnalysisRunStore(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS analysis_runs (id TEXT PRIMARY KEY, dataset_id TEXT NOT NULL, status TEXT NOT NULL, record_json TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS analysis_reviews (run_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, record_json TEXT NOT NULL)");
        } catch (SQLException error) { throw new IOException("Analysis store could not be opened", error); }
        for (var run : list("")) {
            if (!TERMINAL.contains(run.status())) update(new AnalysisRun(
                    run.id(), run.datasetId(), run.annotationId(), run.tool(), "INTERRUPTED",
                    run.createdAt(), run.startedAt(), System.currentTimeMillis(),
                    "Forge restarted before this run completed; submit a new run", run.provenance(),
                    run.configuration(), java.util.Map.of(), run.stale()));
        }
    }

    public synchronized void insert(AnalysisRun run) throws IOException {
        try (var connection = DriverManager.getConnection(url);
                var statement = connection.prepareStatement("INSERT INTO analysis_runs VALUES (?,?,?,?)")) {
            statement.setString(1, run.id()); statement.setString(2, run.datasetId());
            statement.setString(3, run.status()); statement.setString(4, mapper.writeValueAsString(run));
            statement.executeUpdate();
        } catch (SQLException error) { throw new IOException("Analysis run could not be saved", error); }
    }

    // Terminal rows are immutable: cancellation and completion race on this atomic SQL condition.
    public synchronized boolean update(AnalysisRun run) throws IOException {
        try (var connection = DriverManager.getConnection(url);
                var statement = connection.prepareStatement("UPDATE analysis_runs SET status=?,record_json=? WHERE id=? AND status IN ('QUEUED','RUNNING')")) {
            statement.setString(1, run.status()); statement.setString(2, mapper.writeValueAsString(run));
            statement.setString(3, run.id()); return statement.executeUpdate() == 1;
        } catch (SQLException error) { throw new IOException("Analysis status could not be saved", error); }
    }

    public synchronized AnalysisRun get(String id) throws IOException {
        try (var connection = DriverManager.getConnection(url);
                var statement = connection.prepareStatement("SELECT record_json FROM analysis_runs WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Analysis run was not found");
                return mapper.readValue(rows.getString(1), AnalysisRun.class);
            }
        } catch (SQLException error) { throw new IOException("Analysis run could not be read", error); }
    }

    public synchronized List<AnalysisRun> list(String datasetId) throws IOException {
        try (var connection = DriverManager.getConnection(url);
                var statement = connection.prepareStatement("SELECT record_json FROM analysis_runs WHERE (?='' OR dataset_id=?) ORDER BY rowid DESC")) {
            statement.setString(1, datasetId); statement.setString(2, datasetId);
            var result = new ArrayList<AnalysisRun>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(mapper.readValue(rows.getString(1), AnalysisRun.class));
            }
            return List.copyOf(result);
        } catch (SQLException error) { throw new IOException("Analysis runs could not be listed", error); }
    }

    public synchronized java.util.Optional<AnalysisReview> review(String runId) throws IOException {
        try (var connection = DriverManager.getConnection(url);
                var statement = connection.prepareStatement("SELECT record_json FROM analysis_reviews WHERE run_id=?")) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? java.util.Optional.of(mapper.readValue(rows.getString(1), AnalysisReview.class)) : java.util.Optional.empty();
            }
        } catch (SQLException error) { throw new IOException("Analysis review could not be read", error); }
    }

    public synchronized void saveReview(AnalysisReview review, long expectedRevision) throws IOException {
        try (var connection = DriverManager.getConnection(url)) {
            if (expectedRevision == 0) {
                try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO analysis_reviews VALUES (?,?,?)")) {
                    statement.setString(1, review.runId()); statement.setLong(2, review.revision());
                    statement.setString(3, mapper.writeValueAsString(review));
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Analysis review revision changed");
                }
            } else {
                try (var statement = connection.prepareStatement("UPDATE analysis_reviews SET revision=?,record_json=? WHERE run_id=? AND revision=?")) {
                    statement.setLong(1, review.revision()); statement.setString(2, mapper.writeValueAsString(review));
                    statement.setString(3, review.runId()); statement.setLong(4, expectedRevision);
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("Analysis review revision changed");
                }
            }
        } catch (SQLException error) { throw new IOException("Analysis review could not be saved", error); }
    }
}
