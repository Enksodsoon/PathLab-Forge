package org.pathlab.forge.model;

import java.io.Serial;
import java.util.Objects;

public final class InvalidJobTransition extends IllegalStateException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final JobState sourceState;
    private final JobState targetState;

    public InvalidJobTransition(JobState sourceState, JobState targetState) {
        super("Invalid job transition from " + sourceState + " to " + targetState);
        this.sourceState = Objects.requireNonNull(sourceState, "sourceState");
        this.targetState = Objects.requireNonNull(targetState, "targetState");
    }

    public JobState sourceState() {
        return sourceState;
    }

    public JobState targetState() {
        return targetState;
    }
}
