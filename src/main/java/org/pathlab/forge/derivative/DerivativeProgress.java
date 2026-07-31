package org.pathlab.forge.derivative;

import java.util.Objects;

public record DerivativeProgress(String stage, long completedUnits, long totalUnits) {
    public DerivativeProgress {
        stage = Objects.requireNonNull(stage, "stage");
        if (stage.isBlank()
                || completedUnits < 0
                || totalUnits < completedUnits
                || totalUnits < 1) {
            throw new IllegalArgumentException("Derivative progress is invalid");
        }
    }
}
