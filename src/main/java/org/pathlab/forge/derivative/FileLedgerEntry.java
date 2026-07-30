package org.pathlab.forge.derivative;

import java.util.Objects;

public record FileLedgerEntry(
        String path,
        long size,
        String sha256,
        boolean jpegStartMarker,
        boolean jpegEndMarker,
        int width,
        int height) {
    public FileLedgerEntry {
        path = Objects.requireNonNull(path, "path");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (path.isBlank()
                || path.startsWith("/")
                || path.contains("\\")
                || path.contains("../")
                || size < 0
                || !sha256.matches("[0-9a-f]{64}")
                || width < 0
                || height < 0) {
            throw new IllegalArgumentException("File ledger entry is invalid");
        }
    }
}
