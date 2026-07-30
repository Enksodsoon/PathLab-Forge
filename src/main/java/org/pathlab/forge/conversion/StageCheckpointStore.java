package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

public final class StageCheckpointStore {
    private final Path file;

    public StageCheckpointStore(Path revisionRoot) {
        file = revisionRoot.toAbsolutePath().normalize().resolve("checkpoint.properties");
    }

    public Optional<StageCheckpoint> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        var values = new Properties();
        try (var input = Files.newInputStream(file)) {
            values.load(input);
        }
        return Optional.of(new StageCheckpoint(
                values.getProperty("artifactRevisionId"),
                values.getProperty("configurationRevision"),
                values.getProperty("sourceFingerprint"),
                StageCheckpoint.Stage.valueOf(values.getProperty("stage")),
                Long.parseLong(values.getProperty("completedUnits")),
                Long.parseLong(values.getProperty("totalUnits")),
                Long.parseLong(values.getProperty("updatedAt"))));
    }

    public void save(StageCheckpoint checkpoint) throws IOException {
        Files.createDirectories(file.getParent());
        var values = new Properties();
        values.setProperty("artifactRevisionId", checkpoint.artifactRevisionId());
        values.setProperty("configurationRevision", checkpoint.configurationRevision());
        values.setProperty("sourceFingerprint", checkpoint.sourceFingerprint());
        values.setProperty("stage", checkpoint.stage().name());
        values.setProperty("completedUnits", Long.toString(checkpoint.completedUnits()));
        values.setProperty("totalUnits", Long.toString(checkpoint.totalUnits()));
        values.setProperty("updatedAt", Long.toString(checkpoint.updatedAt()));
        var partial = file.resolveSibling(file.getFileName() + ".partial");
        try (var output = Files.newOutputStream(partial)) {
            values.store(output, "PathLab Forge resumable conversion checkpoint");
        }
        try {
            Files.move(
                    partial,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
