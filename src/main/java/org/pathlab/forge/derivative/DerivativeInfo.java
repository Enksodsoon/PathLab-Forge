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
        double meanDeltaE00) {
    public DerivativeInfo(
            Path root, long bytes, int fileCount, int tileCount, String sha256) {
        this(root, bytes, fileCount, tileCount, sha256, java.util.List.of(), 95, 1.0, 0.0);
    }

    public DerivativeInfo(
            Path root,
            long bytes,
            int fileCount,
            int tileCount,
            String sha256,
            java.util.List<FileLedgerEntry> ledger) {
        this(root, bytes, fileCount, tileCount, sha256, ledger, 95, 1.0, 0.0);
    }

    public DerivativeInfo {
        ledger = java.util.List.copyOf(ledger);
        if (!java.util.List.of(85, 90, 95, 100).contains(jpegQuality)
                || !Double.isFinite(minimumWindowedSsim)
                || minimumWindowedSsim < 0
                || minimumWindowedSsim > 1
                || !Double.isFinite(meanDeltaE00)
                || meanDeltaE00 < 0) {
            throw new IllegalArgumentException("Derivative quality evidence is invalid");
        }
    }
}
