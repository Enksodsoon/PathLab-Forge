package org.pathlab.forge.library;

import java.nio.file.Path;

public record ForgePaths(Path dataRoot, Path repositoryFile, Path managedRoot) {
    public static ForgePaths defaults() {
        var localAppData = System.getenv("LOCALAPPDATA");
        Path root;
        if (localAppData != null && !localAppData.isBlank()) {
            root = Path.of(localAppData, "PathLab Forge");
        } else {
            root = Path.of(System.getProperty("user.home"), ".pathlab-forge");
        }
        return new ForgePaths(root, root.resolve("library.properties"), root.resolve("managed"));
    }
}
