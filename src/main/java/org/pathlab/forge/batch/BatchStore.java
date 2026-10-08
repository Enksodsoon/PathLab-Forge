package org.pathlab.forge.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class BatchStore {
    private final String url;
    private final ObjectMapper mapper = new ObjectMapper().setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.IS_GETTER, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE);

    public BatchStore(Path database) throws IOException {
        Files.createDirectories(database.toAbsolutePath().getParent());
        url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("CREATE TABLE IF NOT EXISTS conversion_batches (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, record_json TEXT NOT NULL)");
        } catch (SQLException error) { throw new IOException("Batch database could not be opened", error); }
    }

    public synchronized void save(BatchRun batch) throws IOException {
        if (batch.items().isEmpty() || batch.items().size() > 1000) throw new IllegalArgumentException("Batch must contain 1 to 1000 slides");
        try (var connection = DriverManager.getConnection(url)) {
            try (var settings = connection.createStatement()) {
                settings.execute("PRAGMA busy_timeout=5000");
                settings.execute("PRAGMA synchronous=FULL");
            }
            try (var statement = connection.prepareStatement("INSERT INTO conversion_batches VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET record_json=excluded.record_json")) {
                statement.setString(1, batch.id()); statement.setLong(2, batch.createdAt());
                statement.setString(3, mapper.writeValueAsString(batch)); statement.executeUpdate();
            }
        } catch (SQLException error) { throw new IOException("Batch could not be saved", error); }
    }

    public synchronized BatchRun get(String id) throws IOException {
        try (var connection = DriverManager.getConnection(url);
                var statement = connection.prepareStatement("SELECT record_json FROM conversion_batches WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Batch was not found");
                return mapper.readValue(rows.getString(1), BatchRun.class);
            }
        } catch (SQLException error) { throw new IOException("Batch could not be read", error); }
    }

    public synchronized List<BatchRun> list(int limit, int offset) throws IOException {
        if (limit < 1 || limit > 100 || offset < 0) throw new IllegalArgumentException("Invalid batch page");
        try (var connection = DriverManager.getConnection(url);
                var statement = connection.prepareStatement("SELECT record_json FROM conversion_batches ORDER BY created_at DESC, id LIMIT ? OFFSET ?")) {
            statement.setInt(1, limit); statement.setInt(2, offset);
            var batches = new ArrayList<BatchRun>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) batches.add(mapper.readValue(rows.getString(1), BatchRun.class));
            }
            return List.copyOf(batches);
        } catch (SQLException error) { throw new IOException("Batches could not be listed", error); }
    }
}

