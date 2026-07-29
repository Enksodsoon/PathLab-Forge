package org.pathlab.forge.conversion;

import java.nio.file.Path;
import java.util.Objects;

public record PreviewSource(Path path, int width, int height) {
    public PreviewSource {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Preview dimensions must be positive");
        }
    }
}
