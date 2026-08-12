package org.pathlab.forge.viewer;

import java.nio.file.Path;
import java.util.Objects;

public record CachedViewerResource(Path path, String contentType) {
    public CachedViewerResource {
        Objects.requireNonNull(path);
        Objects.requireNonNull(contentType);
    }
}
