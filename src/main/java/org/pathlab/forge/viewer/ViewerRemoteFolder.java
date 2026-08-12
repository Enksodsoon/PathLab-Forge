package org.pathlab.forge.viewer;

import java.util.Objects;

public record ViewerRemoteFolder(String id, String name, String parentId, long revision) {
    public ViewerRemoteFolder {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(parentId);
        if (revision < 0) {
            throw new IllegalArgumentException("Folder revision must be non-negative");
        }
    }
}
