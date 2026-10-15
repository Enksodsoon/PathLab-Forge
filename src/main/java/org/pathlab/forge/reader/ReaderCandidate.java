package org.pathlab.forge.reader;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ReaderCandidate(
        ReaderDescriptor descriptor,
        Path primaryPath,
        String formatName,
        List<Path> usedFiles,
        long startupMillis) {
    public ReaderCandidate {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        primaryPath = Objects.requireNonNull(primaryPath, "primaryPath").toAbsolutePath().normalize();
        formatName = Objects.requireNonNull(formatName, "formatName").trim();
        usedFiles = List.copyOf(Objects.requireNonNull(usedFiles, "usedFiles"));
        if (formatName.isEmpty() || usedFiles.isEmpty() || startupMillis < 0) {
            throw new IllegalArgumentException("Reader candidate is invalid");
        }
    }
}
