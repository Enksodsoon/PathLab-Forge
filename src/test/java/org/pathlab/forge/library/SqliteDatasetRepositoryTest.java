package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteDatasetRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesLegacyPropertiesOnceAndPersistsThroughWalDatabase() throws Exception {
        var legacy = temporaryDirectory.resolve("library.properties");
        var database = temporaryDirectory.resolve("forge.db");
        var dataset = new LocalDataset(
                "dataset-1",
                "case.ome.tiff",
                temporaryDirectory.resolve("case.ome.tiff").toString(),
                4096,
                DatasetFormat.OME_TIFF,
                DatasetStatus.READY,
                "ready",
                "",
                "");
        new PropertiesDatasetRepository(legacy).save(dataset);

        try (var repository = new SqliteDatasetRepository(database, legacy)) {
            assertEquals(java.util.List.of(dataset), repository.list());
            assertEquals("wal", repository.journalMode().toLowerCase(java.util.Locale.ROOT));
        }

        assertTrue(Files.isRegularFile(
                legacy.resolveSibling("library.properties.pre-sqlite-backup")));
        try (var restarted = new SqliteDatasetRepository(database, legacy)) {
            assertEquals(java.util.List.of(dataset), restarted.list());
        }
    }
}
