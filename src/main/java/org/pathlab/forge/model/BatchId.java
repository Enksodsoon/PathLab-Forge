package org.pathlab.forge.model;

import java.util.Objects;

public record BatchId(String value) {
    public BatchId {
        value = normalized(value, "value");
    }

    public static BatchId of(String value) {
        return new BatchId(value);
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
