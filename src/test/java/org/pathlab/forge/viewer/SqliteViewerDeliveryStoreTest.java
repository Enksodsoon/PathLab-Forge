package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteViewerDeliveryStoreTest {
    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void persistsDeliveryProgressAcrossRestart() throws Exception {
        var database = temporaryDirectory.resolve("forge.db");
        var created = new ViewerDeliveryJob(
                "delivery-1",
                "dataset-1",
                "revision-1",
                "abc123",
                4096,
                "https://viewer.example",
                "ingest-1",
                "https://viewer.example/api/v1/desktop/ingests/ingest-1/content",
                2048,
                ViewerDeliveryState.RETRYING,
                "",
                "",
                "",
                0,
                Instant.parse("2026-08-11T01:00:00Z"),
                Instant.parse("2026-08-11T01:00:04Z"),
                2,
                "connection reset",
                Instant.parse("2026-08-11T01:00:02Z"));

        try (var store = new SqliteViewerDeliveryStore(database)) {
            store.save(created);
        }

        try (var restarted = new SqliteViewerDeliveryStore(database)) {
            assertEquals(created, restarted.find("delivery-1").orElseThrow());
            assertEquals(1, restarted.resumable().size());
        }
    }

    @Test
    void terminalJobsAreNotReturnedForAutomaticResume() throws Exception {
        var database = temporaryDirectory.resolve("forge.db");
        try (var store = new SqliteViewerDeliveryStore(database)) {
            store.save(ViewerDeliveryJob.queued(
                    "delivery-2", "dataset-2", "revision-2", "def456", 1024,
                    "https://viewer.example", Instant.parse("2026-08-11T01:00:00Z"))
                    .withState(ViewerDeliveryState.COMPLETE, "done", Instant.parse("2026-08-11T01:01:00Z")));
            assertTrue(store.resumable().isEmpty());
        }
    }
}
