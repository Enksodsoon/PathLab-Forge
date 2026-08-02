package org.pathlab.forge.viewer;

import java.util.Set;

public record ViewerCapabilities(
        Set<String> ingestModes, long maxChunkBytes, long recommendedChunkBytes) {
    private static final long LEGACY_CHUNK_BYTES = 16L * 1024 * 1024;
    private static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024;

    public ViewerCapabilities {
        ingestModes = Set.copyOf(ingestModes);
        if (maxChunkBytes < 1 || recommendedChunkBytes < 1) {
            throw new IllegalArgumentException("Viewer chunk sizes must be positive");
        }
    }

    public static ViewerCapabilities legacy() {
        return new ViewerCapabilities(Set.of("prepared-v2"), LEGACY_CHUNK_BYTES, LEGACY_CHUNK_BYTES);
    }

    public boolean supportsDynamicOme() {
        return ingestModes.contains("ome-dynamic-v2") || ingestModes.contains("ome-dynamic-v1");
    }

    public int uploadChunkBytes() {
        return Math.toIntExact(Math.min(
                MAX_CHUNK_BYTES, Math.min(maxChunkBytes, recommendedChunkBytes)));
    }
}
