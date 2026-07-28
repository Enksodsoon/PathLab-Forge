package org.pathlab.forge.model;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JobTransitions {
    private static final Map<JobState, Set<JobState>> ALLOWED_TARGETS = Map.ofEntries(
            Map.entry(JobState.PENDING, Set.of(
                    JobState.INSPECTING,
                    JobState.PAUSED,
                    JobState.CANCELLED,
                    JobState.SKIPPED)),
            Map.entry(JobState.INSPECTING, Set.of(
                    JobState.NEEDS_REVIEW,
                    JobState.READY,
                    JobState.FAILED_RETRYABLE,
                    JobState.FAILED_PERMANENT,
                    JobState.CANCEL_REQUESTED)),
            Map.entry(JobState.NEEDS_REVIEW, Set.of(
                    JobState.READY,
                    JobState.PAUSED,
                    JobState.CANCELLED,
                    JobState.SKIPPED,
                    JobState.FAILED_PERMANENT)),
            Map.entry(JobState.READY, Set.of(
                    JobState.EXPORTING_OME,
                    JobState.PAUSED,
                    JobState.CANCELLED,
                    JobState.SKIPPED)),
            Map.entry(JobState.EXPORTING_OME, Set.of(
                    JobState.VALIDATING_OME,
                    JobState.FAILED_RETRYABLE,
                    JobState.FAILED_PERMANENT,
                    JobState.CANCEL_REQUESTED)),
            Map.entry(JobState.VALIDATING_OME, Set.of(
                    JobState.GENERATING_DZI,
                    JobState.FAILED_RETRYABLE,
                    JobState.FAILED_PERMANENT,
                    JobState.CANCEL_REQUESTED)),
            Map.entry(JobState.GENERATING_DZI, Set.of(
                    JobState.VALIDATING_DZI,
                    JobState.FAILED_RETRYABLE,
                    JobState.FAILED_PERMANENT,
                    JobState.CANCEL_REQUESTED)),
            Map.entry(JobState.VALIDATING_DZI, Set.of(
                    JobState.PACKAGING,
                    JobState.FAILED_RETRYABLE,
                    JobState.FAILED_PERMANENT,
                    JobState.CANCEL_REQUESTED)),
            Map.entry(JobState.PACKAGING, Set.of(
                    JobState.READY_TO_UPLOAD,
                    JobState.FAILED_RETRYABLE,
                    JobState.FAILED_PERMANENT,
                    JobState.CANCEL_REQUESTED)),
            Map.entry(JobState.READY_TO_UPLOAD, Set.of(
                    JobState.UPLOADING,
                    JobState.PAUSED,
                    JobState.CANCELLED,
                    JobState.SKIPPED)),
            Map.entry(JobState.UPLOADING, Set.of(
                    JobState.SERVER_PROCESSING,
                    JobState.FAILED_RETRYABLE,
                    JobState.FAILED_PERMANENT,
                    JobState.CANCEL_REQUESTED)),
            Map.entry(JobState.SERVER_PROCESSING, Set.of(
                    JobState.READY_PRIVATE,
                    JobState.FAILED_RETRYABLE,
                    JobState.FAILED_PERMANENT)),
            Map.entry(JobState.READY_PRIVATE, Set.of(JobState.PUBLISHED)),
            Map.entry(JobState.PUBLISHED, Set.of()),
            Map.entry(JobState.PAUSED, Set.of(
                    JobState.PENDING,
                    JobState.NEEDS_REVIEW,
                    JobState.READY,
                    JobState.READY_TO_UPLOAD,
                    JobState.CANCELLED,
                    JobState.SKIPPED)),
            Map.entry(JobState.CANCEL_REQUESTED, Set.of(
                    JobState.CANCELLED,
                    JobState.FAILED_RETRYABLE)),
            Map.entry(JobState.CANCELLED, Set.of()),
            Map.entry(JobState.FAILED_RETRYABLE, Set.of(
                    JobState.PENDING,
                    JobState.READY,
                    JobState.READY_TO_UPLOAD,
                    JobState.CANCELLED,
                    JobState.SKIPPED)),
            Map.entry(JobState.FAILED_PERMANENT, Set.of()),
            Map.entry(JobState.SKIPPED, Set.of()));

    private JobTransitions() {}

    public static JobState transition(JobState source, JobState target) {
        if (!canTransition(source, target)) {
            throw new InvalidJobTransition(source, target);
        }
        return target;
    }

    public static boolean canTransition(JobState source, JobState target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return ALLOWED_TARGETS.get(source).contains(target);
    }

    public static Set<JobState> allowedTargets(JobState source) {
        return ALLOWED_TARGETS.get(Objects.requireNonNull(source, "source"));
    }
}
