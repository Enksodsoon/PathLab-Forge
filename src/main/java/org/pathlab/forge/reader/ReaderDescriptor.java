package org.pathlab.forge.reader;

import java.util.List;
import java.util.Objects;

public record ReaderDescriptor(
        String engine,
        String readerId,
        String displayName,
        List<String> extensions,
        boolean multidimensional,
        boolean nativePyramid,
        boolean groupedFiles,
        boolean randomRegions) {
    public ReaderDescriptor {
        engine = requireText(engine, "engine");
        readerId = requireText(readerId, "readerId");
        displayName = requireText(displayName, "displayName");
        extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
