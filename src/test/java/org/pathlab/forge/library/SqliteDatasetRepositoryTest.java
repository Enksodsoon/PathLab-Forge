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

    @Test
    void marksInterruptedConversionsRetryableOnRestart() throws Exception {
        var legacy = temporaryDirectory.resolve("library.properties");
        var database = temporaryDirectory.resolve("forge.db");
        var dataset = new LocalDataset(
                "dataset-1",
                "case.vsi",
                temporaryDirectory.resolve("case.vsi").toString(),
                4096,
                DatasetFormat.VSI,
                DatasetStatus.OPTIMIZING_OME,
                "Writing the final OME pyramid",
                "",
                "");

        try (var repository = new SqliteDatasetRepository(database, legacy)) {
            repository.save(dataset);
        }

        try (var restarted = new SqliteDatasetRepository(database, legacy)) {
            var recovered = restarted.find("dataset-1").orElseThrow();
            assertEquals(DatasetStatus.FAILED, recovered.status());
            assertEquals(
                    "Interrupted by application restart; retry is safe",
                    recovered.detail());
        }
    }

    @Test
    void reloadsAPreviouslyPersistedSvsDataset() throws Exception {
        var database = temporaryDirectory.resolve("forge.db");
        var legacy = temporaryDirectory.resolve("library.properties");
        var source = temporaryDirectory.resolve("legacy.svs");
        Files.write(source, new byte[] {'I', 'I', 42, 0});
        var dataset = new LocalDataset(
                "legacy-svs", "legacy.svs", source.toString(), 4,
                DatasetFormat.SVS, DatasetStatus.READY, "ready", "", "");

        try (var repository = new SqliteDatasetRepository(database, legacy)) {
            repository.save(dataset);
        }
        try (var restarted = new SqliteDatasetRepository(database, legacy)) {
            assertEquals(DatasetFormat.SVS, restarted.find("legacy-svs").orElseThrow().format());
        }
    }

    @Test
    void persistsConversionQueueOrderAndConfigurationSnapshot() throws Exception {
        var database = temporaryDirectory.resolve("forge.db");
        var legacy = temporaryDirectory.resolve("library.properties");
        var queued = new ConversionQueueEntry(
                "dataset-1", 7, "config-abc", 1234L, "OME_DYNAMIC_V1", "Waiting for memory");

        try (var repository = new SqliteDatasetRepository(database, legacy)) {
            repository.saveQueueEntry(queued);
        }
        try (var repository = new SqliteDatasetRepository(database, legacy)) {
            assertEquals(java.util.List.of(queued), repository.listQueueEntries());
            repository.deleteQueueEntry("dataset-1");
            assertEquals(java.util.List.of(), repository.listQueueEntries());
        }
    }
}
