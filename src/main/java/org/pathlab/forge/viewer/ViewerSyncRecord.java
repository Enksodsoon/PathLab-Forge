package org.pathlab.forge.viewer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public record ViewerSyncRecord(
        ViewerRemoteSlide remote,
        Set<String> dirtyFields,
        Path partialPath,
        long downloadBytes,
        long downloadOffset,
        String downloadSha256) {
    public ViewerSyncRecord {
        Objects.requireNonNull(remote);
        dirtyFields = Set.copyOf(dirtyFields);
        Objects.requireNonNull(partialPath);
        Objects.requireNonNull(downloadSha256);
        if (downloadBytes < 0 || downloadOffset < 0 || downloadOffset > downloadBytes) {
            throw new IllegalArgumentException("Download counters are invalid");
        }
    }
}
