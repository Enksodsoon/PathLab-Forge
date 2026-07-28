package org.pathlab.forge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobTransitionsTest {
    @Test
    void followsTheCompleteSuccessfulPath() {
        assertPath(
                JobState.PENDING,
                JobState.INSPECTING,
                JobState.READY,
                JobState.EXPORTING_OME,
                JobState.VALIDATING_OME,
                JobState.GENERATING_DZI,
                JobState.VALIDATING_DZI,
                JobState.PACKAGING,
                JobState.READY_TO_UPLOAD,
                JobState.UPLOADING,
                JobState.SERVER_PROCESSING,
                JobState.READY_PRIVATE,
                JobState.PUBLISHED);
    }

    @Test
    void followsReviewRetryCancellationAndPausePaths() {
        assertPath(
                JobState.PENDING,
                JobState.INSPECTING,
                JobState.NEEDS_REVIEW,
                JobState.READY);
        assertPath(
                JobState.EXPORTING_OME,
                JobState.FAILED_RETRYABLE,
                JobState.READY);
        assertPath(
                JobState.UPLOADING,
                JobState.FAILED_RETRYABLE,
                JobState.READY_TO_UPLOAD);
        assertPath(
                JobState.GENERATING_DZI,
                JobState.CANCEL_REQUESTED,
                JobState.CANCELLED);
        assertPath(
                JobState.READY,
                JobState.PAUSED,
                JobState.READY);
    }

    @Test
    void rejectsInvalidDirectJumpsAndNoOpTransitions() {
        Map<JobState, JobState> invalid = Map.of(
                JobState.PENDING, JobState.READY_PRIVATE,
                JobState.READY, JobState.READY_TO_UPLOAD,
                JobState.EXPORTING_OME, JobState.PUBLISHED,
                JobState.UPLOADING, JobState.READY_PRIVATE,
                JobState.READY_PRIVATE, JobState.EXPORTING_OME,
                JobState.FAILED_PERMANENT, JobState.PENDING,
                JobState.CANCELLED, JobState.READY,
                JobState.PUBLISHED, JobState.READY_PRIVATE);

        invalid.forEach((source, target) -> {
            assertFalse(JobTransitions.canTransition(source, target));
            InvalidJobTransition error = assertThrows(
                    InvalidJobTransition.class,
                    () -> JobTransitions.transition(source, target));
            assertEquals(source, error.sourceState());
            assertEquals(target, error.targetState());
            assertTrue(error.getMessage().contains(source.name()));
            assertTrue(error.getMessage().contains(target.name()));
        });
        for (JobState state : JobState.values()) {
            assertFalse(JobTransitions.canTransition(state, state));
        }
    }

    @Test
    void exposesTheExactAuditableTransitionTable() {
        Map<JobState, Set<JobState>> expected = Map.ofEntries(
                Map.entry(JobState.PENDING, Set.of(
                        JobState.INSPECTING, JobState.PAUSED, JobState.CANCELLED, JobState.SKIPPED)),
                Map.entry(JobState.INSPECTING, Set.of(
                        JobState.NEEDS_REVIEW, JobState.READY, JobState.FAILED_RETRYABLE,
                        JobState.FAILED_PERMANENT, JobState.CANCEL_REQUESTED)),
                Map.entry(JobState.NEEDS_REVIEW, Set.of(
                        JobState.READY, JobState.PAUSED, JobState.CANCELLED,
                        JobState.SKIPPED, JobState.FAILED_PERMANENT)),
                Map.entry(JobState.READY, Set.of(
                        JobState.EXPORTING_OME, JobState.PAUSED, JobState.CANCELLED, JobState.SKIPPED)),
                Map.entry(JobState.EXPORTING_OME, Set.of(
                        JobState.VALIDATING_OME, JobState.FAILED_RETRYABLE,
                        JobState.FAILED_PERMANENT, JobState.CANCEL_REQUESTED)),
                Map.entry(JobState.VALIDATING_OME, Set.of(
                        JobState.GENERATING_DZI, JobState.FAILED_RETRYABLE,
                        JobState.FAILED_PERMANENT, JobState.CANCEL_REQUESTED)),
                Map.entry(JobState.GENERATING_DZI, Set.of(
                        JobState.VALIDATING_DZI, JobState.FAILED_RETRYABLE,
                        JobState.FAILED_PERMANENT, JobState.CANCEL_REQUESTED)),
                Map.entry(JobState.VALIDATING_DZI, Set.of(
                        JobState.PACKAGING, JobState.FAILED_RETRYABLE,
                        JobState.FAILED_PERMANENT, JobState.CANCEL_REQUESTED)),
                Map.entry(JobState.PACKAGING, Set.of(
                        JobState.READY_TO_UPLOAD, JobState.FAILED_RETRYABLE,
                        JobState.FAILED_PERMANENT, JobState.CANCEL_REQUESTED)),
                Map.entry(JobState.READY_TO_UPLOAD, Set.of(
                        JobState.UPLOADING, JobState.PAUSED, JobState.CANCELLED, JobState.SKIPPED)),
                Map.entry(JobState.UPLOADING, Set.of(
                        JobState.SERVER_PROCESSING, JobState.FAILED_RETRYABLE,
                        JobState.FAILED_PERMANENT, JobState.CANCEL_REQUESTED)),
                Map.entry(JobState.SERVER_PROCESSING, Set.of(
                        JobState.READY_PRIVATE, JobState.FAILED_RETRYABLE, JobState.FAILED_PERMANENT)),
                Map.entry(JobState.READY_PRIVATE, Set.of(JobState.PUBLISHED)),
                Map.entry(JobState.PUBLISHED, Set.of()),
                Map.entry(JobState.PAUSED, Set.of(
                        JobState.PENDING, JobState.NEEDS_REVIEW, JobState.READY,
                        JobState.READY_TO_UPLOAD, JobState.CANCELLED, JobState.SKIPPED)),
                Map.entry(JobState.CANCEL_REQUESTED, Set.of(
                        JobState.CANCELLED, JobState.FAILED_RETRYABLE)),
                Map.entry(JobState.CANCELLED, Set.of()),
                Map.entry(JobState.FAILED_RETRYABLE, Set.of(
                        JobState.PENDING, JobState.READY, JobState.READY_TO_UPLOAD,
                        JobState.CANCELLED, JobState.SKIPPED)),
                Map.entry(JobState.FAILED_PERMANENT, Set.of()),
                Map.entry(JobState.SKIPPED, Set.of()));

        for (JobState state : JobState.values()) {
            assertEquals(expected.get(state), JobTransitions.allowedTargets(state), state.name());
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (JobState terminal : Set.of(
                JobState.PUBLISHED,
                JobState.CANCELLED,
                JobState.FAILED_PERMANENT,
                JobState.SKIPPED)) {
            assertTrue(JobTransitions.allowedTargets(terminal).isEmpty());
        }
    }

    @Test
    void returnedTargetSetsAreImmutable() {
        Set<JobState> targets = JobTransitions.allowedTargets(JobState.PENDING);

        assertThrows(UnsupportedOperationException.class, () -> targets.add(JobState.PUBLISHED));
    }

    @Test
    void rejectsNullStates() {
        assertThrows(
                NullPointerException.class,
                () -> JobTransitions.transition(null, JobState.READY));
        assertThrows(
                NullPointerException.class,
                () -> JobTransitions.transition(JobState.READY, null));
        assertThrows(
                NullPointerException.class,
                () -> JobTransitions.canTransition(null, JobState.READY));
        assertThrows(
                NullPointerException.class,
                () -> JobTransitions.canTransition(JobState.READY, null));
        assertThrows(NullPointerException.class, () -> JobTransitions.allowedTargets(null));
    }

    private static void assertPath(JobState initial, JobState... targets) {
        JobState current = initial;
        for (JobState target : targets) {
            assertTrue(JobTransitions.canTransition(current, target));
            assertEquals(target, JobTransitions.transition(current, target));
            current = target;
        }
    }
}
