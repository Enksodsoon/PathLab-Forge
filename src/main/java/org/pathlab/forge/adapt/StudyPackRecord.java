package org.pathlab.forge.adapt;

import java.nio.file.Path;
import java.util.Objects;

public record StudyPackRecord(
        String packKey,
        int version,
        String title,
        String checksum,
        boolean masteryEligible,
        Path path) {
    public StudyPackRecord {
        packKey = requireText(packKey, "packKey");
        title = requireText(title, "title");
        checksum = requireText(checksum, "checksum");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (version < 1 || !checksum.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Study Pack metadata is invalid");
        }
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
