package org.pathlab.forge.model;

import java.time.Instant;
import java.util.Objects;

public record SlideJob(
        JobId jobId,
        BatchId batchId,
        String sourcePath,
        String displayName,
        int queuePosition,
        JobState state,
        int retryCount,
        Instant createdAt,
        Instant updatedAt) {

    public SlideJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(batchId, "batchId");
        requireNonBlank(sourcePath, "sourcePath");
        requireNonBlank(displayName, "displayName");
        if (queuePosition < 0) {
            throw new IllegalArgumentException("queuePosition must be at least zero");
        }
        Objects.requireNonNull(state, "state");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must be at least zero");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be earlier than createdAt");
        }
    }

    public SlideJob withState(JobState nextState, Instant nextUpdatedAt) {
        return new SlideJob(
                jobId,
                batchId,
                sourcePath,
                displayName,
                queuePosition,
                JobTransitions.transition(state, nextState),
                retryCount,
                createdAt,
                nextUpdatedAt);
    }

    public SlideJob withRetryCount(int nextRetryCount, Instant nextUpdatedAt) {
        return new SlideJob(
                jobId,
                batchId,
                sourcePath,
                displayName,
                queuePosition,
                state,
                nextRetryCount,
                createdAt,
                nextUpdatedAt);
    }

    public SlideJob withQueuePosition(int nextQueuePosition, Instant nextUpdatedAt) {
        return new SlideJob(
                jobId,
                batchId,
                sourcePath,
                displayName,
                nextQueuePosition,
                state,
                retryCount,
                createdAt,
                nextUpdatedAt);
    }

    public SlideJob withDisplayName(String nextDisplayName, Instant nextUpdatedAt) {
        return new SlideJob(
                jobId,
                batchId,
                sourcePath,
                nextDisplayName,
                queuePosition,
                state,
                retryCount,
                createdAt,
                nextUpdatedAt);
    }

    private static void requireNonBlank(String value, String name) {
        if (Objects.requireNonNull(value, name).trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
