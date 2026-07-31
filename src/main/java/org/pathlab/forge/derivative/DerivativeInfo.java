package org.pathlab.forge.derivative;

import java.nio.file.Path;

public record DerivativeInfo(
        Path root,
        long bytes,
        int fileCount,
        int tileCount,
        String sha256,
        java.util.List<FileLedgerEntry> ledger,
        int jpegQuality,
        double minimumWindowedSsim,
        double meanDeltaE00,
        double minimumEdgeDetailRetention,
        String encoderProfile) {
    public DerivativeInfo(
            Path root, long bytes, int fileCount, int tileCount, String sha256) {
        this(root, bytes, fileCount, tileCount, sha256, java.util.List.of(), 75, 1.0, 0.0, 1.0, "compact-baseline");
    }

    public DerivativeInfo(
            Path root,
            long bytes,
            int fileCount,
            int tileCount,
            String sha256,
            java.util.List<FileLedgerEntry> ledger) {
        this(root, bytes, fileCount, tileCount, sha256, ledger, 75, 1.0, 0.0, 1.0, "compact-baseline");
    }

    public DerivativeInfo {
        ledger = java.util.List.copyOf(ledger);
        if (!java.util.List.of(65, 70, 75, 80).contains(jpegQuality)
                || !Double.isFinite(minimumWindowedSsim)
                || minimumWindowedSsim < 0
                || minimumWindowedSsim > 1
                || !Double.isFinite(meanDeltaE00)
                || meanDeltaE00 < 0
                || !Double.isFinite(minimumEdgeDetailRetention)
                || minimumEdgeDetailRetention < 0
                || minimumEdgeDetailRetention > 1
                || encoderProfile == null
                || encoderProfile.isBlank()) {
            throw new IllegalArgumentException("Derivative quality evidence is invalid");
        }
    }
}
