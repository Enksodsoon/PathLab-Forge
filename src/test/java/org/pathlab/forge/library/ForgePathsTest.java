package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ForgePathsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void derivesEveryPersistentPathFromExplicitDataRoot() {
        var root = temporaryDirectory.resolve("isolated");
        var paths = ForgePaths.at(root);

        assertEquals(root.toAbsolutePath().normalize(), paths.dataRoot());
        assertEquals(paths.dataRoot().resolve("forge.db"), paths.repositoryFile());
        assertEquals(paths.dataRoot().resolve("managed"), paths.managedRoot());
    }
}
