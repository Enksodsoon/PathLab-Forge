package org.pathlab.forge.packageformat;

import java.nio.file.Path;

public record PackageInfo(
        Path path,
        long bytes,
        String sha256,
        long derivativeBytes,
        int derivativeFileCount,
        PackageEntryIndex entryIndex) {
    public PackageInfo(
            Path path,
            long bytes,
            String sha256,
            long derivativeBytes,
            int derivativeFileCount) {
        this(
                path,
                bytes,
                sha256,
                derivativeBytes,
                derivativeFileCount,
                new PackageEntryIndex(java.util.Map.of()));
    }
}
