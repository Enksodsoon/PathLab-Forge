package org.pathlab.forge.model;

import java.util.Objects;

public record JobId(String value) {
    public JobId {
        value = normalized(value, "value");
    }

    public static JobId of(String value) {
        return new JobId(value);
    }

    @Override
    public String toString() {
        return value;
    }

    private static String normalized(String value, String name) {
        String trimmed = Objects.requireNonNull(value, name).trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
