package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceJobQueueTest {
    @TempDir java.nio.file.Path temporaryDirectory;

    @Test
    void resumesExpiredLeaseAcrossRestartAndCompletes() throws Exception {
        var database = temporaryDirectory.resolve("jobs.sqlite");
        var request = temporaryDirectory.resolve("request.json");
        Files.writeString(request, "{}");
        var start = Instant.parse("2026-08-22T00:00:00Z");
        try (var queue = new EvidenceJobQueue(database)) {
            queue.submit("job-1", request, start);
            var claimed = queue.claimNext("worker-a", start, Duration.ofSeconds(30)).orElseThrow();
            assertEquals(EvidenceJobState.VALIDATING, claimed.state());
            queue.checkpoint("job-1", "worker-a", EvidenceJobState.RUNNING, "coarse", .2,
                    "Coarse evidence", start.plusSeconds(1), Duration.ofSeconds(30));
        }
        try (var restarted = new EvidenceJobQueue(database)) {
            assertTrue(restarted.claimNext("worker-b", start.plusSeconds(32), Duration.ofSeconds(30)).isPresent());
            restarted.checkpoint("job-1", "worker-b", EvidenceJobState.PACKAGING, "packaging", .9,
                    "Packaging", start.plusSeconds(33), Duration.ofSeconds(30));
            restarted.checkpoint("job-1", "worker-b", EvidenceJobState.COMPLETED, "completed", 1,
                    "Complete", start.plusSeconds(34), Duration.ofSeconds(30));
            assertEquals(EvidenceJobState.COMPLETED, restarted.find("job-1").orElseThrow().state());
            assertFalse(restarted.claimNext("worker-c", start.plusSeconds(60), Duration.ofSeconds(30)).isPresent());
        }
    }

    @Test
    void cancellationFlagSurvivesRestart() throws Exception {
        var database = temporaryDirectory.resolve("jobs.sqlite");
        var request = temporaryDirectory.resolve("request.json");
        Files.writeString(request, "{}");
        var now = Instant.parse("2026-08-22T00:00:00Z");
        try (var queue = new EvidenceJobQueue(database)) {
            queue.submit("job-2", request, now);
            queue.requestCancel("job-2", now.plusSeconds(1));
        }
        try (var queue = new EvidenceJobQueue(database)) {
            assertTrue(queue.find("job-2").orElseThrow().cancelRequested());
            assertTrue(queue.claimNext("worker", now.plusSeconds(2), Duration.ofSeconds(30)).isEmpty());
        }
    }
}
