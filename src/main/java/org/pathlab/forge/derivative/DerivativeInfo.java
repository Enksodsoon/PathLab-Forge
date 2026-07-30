package org.pathlab.forge.derivative;

import java.nio.file.Path;

public record DerivativeInfo(
        Path root,
        long bytes,
        int fileCount,
        int tileCount,
        String sha256,
        java.util.List<FileLedgerEntry> ledger) {
    public DerivativeInfo(
            Path root, long bytes, int fileCount, int tileCount, String sha256) {
        this(root, bytes, fileCount, tileCount, sha256, java.util.List.of());
    }

    public DerivativeInfo {
        ledger = java.util.List.copyOf(ledger);
    }
}
