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

    @Test
    void separatesExecutionLanesAndRenewsLiveLease() throws Exception {
        var database = temporaryDirectory.resolve("jobs.sqlite");
        var gpuRequest = temporaryDirectory.resolve("gpu.json");
        var cpuRequest = temporaryDirectory.resolve("cpu.json");
        Files.writeString(gpuRequest, "{}");
        Files.writeString(cpuRequest, "{}");
        var now = Instant.parse("2026-08-22T00:00:00Z");
        try (var queue = new EvidenceJobQueue(database)) {
            queue.submit("gpu-job", gpuRequest, EvidenceExecutionLane.GPU, now);
            queue.submit("cpu-job", cpuRequest, EvidenceExecutionLane.CPU_IO, now.plusMillis(1));

            var cpu = queue.claimNext(EvidenceExecutionLane.CPU_IO, "cpu-worker", now,
                    Duration.ofSeconds(30)).orElseThrow();
            assertEquals("cpu-job", cpu.id());
            var gpu = queue.claimNext(EvidenceExecutionLane.GPU, "gpu-worker", now,
                    Duration.ofSeconds(30)).orElseThrow();
            assertEquals("gpu-job", gpu.id());

            queue.heartbeat("gpu-job", "gpu-worker", 32, 100,
                    temporaryDirectory.resolve("gpu.checkpoint"), "a".repeat(64),
                    now.plusSeconds(15), Duration.ofSeconds(30));
            var snapshot = queue.snapshot("gpu-job").orElseThrow();
            assertEquals(32, snapshot.completedUnits());
            assertEquals(100, snapshot.totalUnits());
            assertEquals(now.plusSeconds(45), snapshot.leaseExpiresAt());
            assertEquals(null, snapshot.etaSeconds());
            queue.heartbeat("gpu-job", "gpu-worker", 64, 100,
                    temporaryDirectory.resolve("gpu.checkpoint"), "a".repeat(64),
                    now.plusSeconds(30), Duration.ofSeconds(30));
            snapshot = queue.snapshot("gpu-job").orElseThrow();
            assertTrue(snapshot.throughput() > 0);
            assertTrue(snapshot.etaSeconds() != null);
        }
    }

    @Test
    void pauseStopsClaimsAndOperatorRetryUsesBoundedDelay() throws Exception {
        var database = temporaryDirectory.resolve("jobs.sqlite");
        var request = temporaryDirectory.resolve("request.json");
        Files.writeString(request, "{}");
        var now = Instant.parse("2026-08-22T00:00:00Z");
        try (var queue = new EvidenceJobQueue(database)) {
            queue.submit("job", request, EvidenceExecutionLane.CPU_IO, now);
            queue.setAcceptingJobs(false, now.plusSeconds(1));
            assertFalse(queue.acceptingJobs());
            assertTrue(queue.claimNext(EvidenceExecutionLane.CPU_IO, "worker", now.plusSeconds(2),
                    Duration.ofSeconds(30)).isEmpty());
            queue.setAcceptingJobs(true, now.plusSeconds(3));
            var claimed = queue.claimNext(EvidenceExecutionLane.CPU_IO, "worker", now.plusSeconds(4),
                    Duration.ofSeconds(30)).orElseThrow();
            queue.fail(claimed.id(), "worker", "transient_io", "IO_READ_FAILED", "disk busy",
                    true, now.plusSeconds(5));
            var retry = queue.snapshot("job").orElseThrow();
            assertEquals(EvidenceJobState.QUEUED, retry.state());
            assertEquals(now.plusSeconds(10), retry.nextRetryAt());
            assertTrue(queue.claimNext(EvidenceExecutionLane.CPU_IO, "worker-2", now.plusSeconds(9),
                    Duration.ofSeconds(30)).isEmpty());
        }
    }
}
