package org.pathlab.forge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlideJobTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-29T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-29T00:05:00Z");

    @Test
    void constructsAValidImmutableJob() {
        SlideJob job = job(JobState.PENDING);

        assertEquals(JobId.of("job-001"), job.jobId());
        assertEquals(BatchId.of("batch-001"), job.batchId());
        assertEquals("slides/case.ome.tiff", job.sourcePath());
        assertEquals("Case 001", job.displayName());
        assertEquals(0, job.queuePosition());
        assertEquals(JobState.PENDING, job.state());
        assertEquals(0, job.retryCount());
        assertEquals(CREATED_AT, job.createdAt());
        assertEquals(UPDATED_AT, job.updatedAt());
    }

    @Test
    void rejectsNullRequiredFields() {
        SlideJob valid = job(JobState.PENDING);

        assertThrows(NullPointerException.class, () -> new SlideJob(
                null,
                valid.batchId(),
                valid.sourcePath(),
                valid.displayName(),
                valid.queuePosition(),
                valid.state(),
                valid.retryCount(),
                valid.createdAt(),
                valid.updatedAt()));
        assertThrows(NullPointerException.class, () -> new SlideJob(
                valid.jobId(),
                null,
                valid.sourcePath(),
                valid.displayName(),
                valid.queuePosition(),
                valid.state(),
                valid.retryCount(),
                valid.createdAt(),
                valid.updatedAt()));
        assertThrows(NullPointerException.class, () -> new SlideJob(
                valid.jobId(),
                valid.batchId(),
                null,
                valid.displayName(),
                valid.queuePosition(),
                valid.state(),
                valid.retryCount(),
                valid.createdAt(),
                valid.updatedAt()));
        assertThrows(NullPointerException.class, () -> new SlideJob(
                valid.jobId(),
                valid.batchId(),
                valid.sourcePath(),
                null,
                valid.queuePosition(),
                valid.state(),
                valid.retryCount(),
                valid.createdAt(),
                valid.updatedAt()));
        assertThrows(NullPointerException.class, () -> new SlideJob(
                valid.jobId(),
                valid.batchId(),
                valid.sourcePath(),
                valid.displayName(),
                valid.queuePosition(),
                null,
                valid.retryCount(),
                valid.createdAt(),
                valid.updatedAt()));
        assertThrows(NullPointerException.class, () -> new SlideJob(
                valid.jobId(),
                valid.batchId(),
                valid.sourcePath(),
                valid.displayName(),
                valid.queuePosition(),
                valid.state(),
                valid.retryCount(),
                null,
                valid.updatedAt()));
        assertThrows(NullPointerException.class, () -> new SlideJob(
                valid.jobId(),
                valid.batchId(),
                valid.sourcePath(),
                valid.displayName(),
                valid.queuePosition(),
                valid.state(),
                valid.retryCount(),
                valid.createdAt(),
                null));
    }

    @Test
    void rejectsBlankPathsAndNames() {
        SlideJob valid = job(JobState.PENDING);

        assertThrows(IllegalArgumentException.class, () -> new SlideJob(
                valid.jobId(), valid.batchId(), " ", valid.displayName(), 0,
                valid.state(), 0, valid.createdAt(), valid.updatedAt()));
        assertThrows(IllegalArgumentException.class, () -> new SlideJob(
                valid.jobId(), valid.batchId(), valid.sourcePath(), "\t", 0,
                valid.state(), 0, valid.createdAt(), valid.updatedAt()));
    }

    @Test
    void rejectsNegativePositionsAndRetryCounts() {
        SlideJob valid = job(JobState.PENDING);

        assertThrows(IllegalArgumentException.class, () -> new SlideJob(
                valid.jobId(), valid.batchId(), valid.sourcePath(), valid.displayName(), -1,
                valid.state(), 0, valid.createdAt(), valid.updatedAt()));
        assertThrows(IllegalArgumentException.class, () -> new SlideJob(
                valid.jobId(), valid.batchId(), valid.sourcePath(), valid.displayName(), 0,
                valid.state(), -1, valid.createdAt(), valid.updatedAt()));
    }

    @Test
    void rejectsUpdatedTimeBeforeCreatedTime() {
        SlideJob valid = job(JobState.PENDING);

        assertThrows(IllegalArgumentException.class, () -> new SlideJob(
                valid.jobId(),
                valid.batchId(),
                valid.sourcePath(),
                valid.displayName(),
                0,
                valid.state(),
                0,
                CREATED_AT,
                CREATED_AT.minusSeconds(1)));
    }

    @Test
    void changesStateThroughTheCentralTransitionValidator() {
        SlideJob original = job(JobState.PENDING);
        Instant nextTime = UPDATED_AT.plusSeconds(1);

        SlideJob changed = original.withState(JobState.INSPECTING, nextTime);

        assertNotSame(original, changed);
        assertEquals(JobState.PENDING, original.state());
        assertEquals(UPDATED_AT, original.updatedAt());
        assertEquals(JobState.INSPECTING, changed.state());
        assertEquals(nextTime, changed.updatedAt());
        assertEquals(original.jobId(), changed.jobId());
        assertThrows(
                InvalidJobTransition.class,
                () -> original.withState(JobState.READY_PRIVATE, nextTime));
    }

    @Test
    void updatesRetryCountWithoutMutatingOtherFields() {
        SlideJob original = job(JobState.PENDING);
        Instant nextTime = UPDATED_AT.plusSeconds(2);

        SlideJob changed = original.withRetryCount(2, nextTime);

        assertEquals(0, original.retryCount());
        assertEquals(2, changed.retryCount());
        assertEquals(nextTime, changed.updatedAt());
        assertEquals(original.jobId(), changed.jobId());
        assertEquals(original.state(), changed.state());
        assertThrows(IllegalArgumentException.class, () -> original.withRetryCount(-1, nextTime));
    }

    @Test
    void updatesQueuePositionWithoutMutatingOtherFields() {
        SlideJob original = job(JobState.PENDING);
        Instant nextTime = UPDATED_AT.plusSeconds(3);

        SlideJob changed = original.withQueuePosition(4, nextTime);

        assertEquals(0, original.queuePosition());
        assertEquals(4, changed.queuePosition());
        assertEquals(nextTime, changed.updatedAt());
        assertEquals(original.displayName(), changed.displayName());
        assertThrows(IllegalArgumentException.class, () -> original.withQueuePosition(-1, nextTime));
    }

    @Test
    void updatesDisplayNameWithoutMutatingOtherFields() {
        SlideJob original = job(JobState.PENDING);
        Instant nextTime = UPDATED_AT.plusSeconds(4);

        SlideJob changed = original.withDisplayName("Renamed case", nextTime);

        assertEquals("Case 001", original.displayName());
        assertEquals("Renamed case", changed.displayName());
        assertEquals(nextTime, changed.updatedAt());
        assertEquals(original.sourcePath(), changed.sourcePath());
        assertThrows(IllegalArgumentException.class, () -> original.withDisplayName(" ", nextTime));
    }

    private static SlideJob job(JobState state) {
        return new SlideJob(
                JobId.of("job-001"),
                BatchId.of("batch-001"),
                "slides/case.ome.tiff",
                "Case 001",
                0,
                state,
                0,
                CREATED_AT,
                UPDATED_AT);
    }
}
