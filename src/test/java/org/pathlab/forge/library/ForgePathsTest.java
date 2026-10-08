package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ForgePathsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void migrationPreservesOriginalAndDoesNotOverwriteExistingData() throws Exception {
        var legacy = temporaryDirectory.resolve("legacy");
        var target = temporaryDirectory.resolve("new");
        java.nio.file.Files.createDirectories(legacy.resolve("managed"));
        java.nio.file.Files.createDirectories(target);
        java.nio.file.Files.writeString(legacy.resolve("forge.db"), "queue");
        java.nio.file.Files.writeString(legacy.resolve("managed/package.plslide"), "package");
        ForgePaths.migrateLegacyData(legacy, target);
        assertEquals("queue", java.nio.file.Files.readString(target.resolve("forge.db")));
        assertEquals("queue", java.nio.file.Files.readString(legacy.resolve("forge.db")));
        assertEquals("package", java.nio.file.Files.readString(target.resolve("managed/package.plslide")));
        java.nio.file.Files.writeString(legacy.resolve("forge.db"), "old-updated");
        ForgePaths.migrateLegacyData(legacy, target);
        assertEquals("queue", java.nio.file.Files.readString(target.resolve("forge.db")));
    }

    @Test
    @SuppressWarnings("try")
    void migrationRejectsAnActiveLegacyOwner() throws Exception {
        var legacy = temporaryDirectory.resolve("busy");
        try (var owner = org.pathlab.forge.runtime.DataRootLock.acquire(legacy)) {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> ForgePaths.migrateLegacyData(legacy, temporaryDirectory.resolve("destination")));
        }
    }

    @Test
    void derivesEveryPersistentPathFromExplicitDataRoot() {
        var root = temporaryDirectory.resolve("isolated");
        var paths = ForgePaths.at(root);

        assertEquals(root.toAbsolutePath().normalize(), paths.dataRoot());
        assertEquals(paths.dataRoot().resolve("forge.db"), paths.repositoryFile());
        assertEquals(paths.dataRoot().resolve("managed"), paths.managedRoot());
    }
}
