package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QueuePausePersistenceTest {
    @TempDir Path root;

    @Test void sqlitePauseSurvivesRestartAndResume() throws Exception {
        var database = root.resolve("forge.db");
        var legacy = root.resolve("library.properties");
        try (var repository = new SqliteDatasetRepository(database, legacy)) {
            assertFalse(repository.queuePaused());
            repository.setQueuePaused(true);
        }
        try (var repository = new SqliteDatasetRepository(database, legacy)) {
            assertTrue(repository.queuePaused());
            repository.setQueuePaused(false);
        }
        try (var repository = new SqliteDatasetRepository(database, legacy)) {
            assertFalse(repository.queuePaused());
        }
    }

    @Test void legacyPauseSurvivesRestart() throws Exception {
        var path = root.resolve("library.properties");
        var repository = new PropertiesDatasetRepository(path);
        repository.setQueuePaused(true);
        assertTrue(new PropertiesDatasetRepository(path).queuePaused());
    }
}
