package org.pathlab.forge.reader;

import java.nio.file.Path;
import java.util.List;
import java.io.IOException;

@FunctionalInterface
public interface DatasetProbe {
    Result probe(Path source) throws ImportProbeException, IOException;

    record Result(
            ReaderDescriptor descriptor,
            String formatName,
            List<Path> usedFiles,
            String runtimeFingerprint) {
        public Result {
            usedFiles = List.copyOf(usedFiles);
            if (usedFiles.isEmpty() || !runtimeFingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Probe result is invalid");
            }
        }
    }
}
