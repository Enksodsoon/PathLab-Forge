package org.pathlab.forge.library;

import java.nio.file.Path;

public record ForgePaths(Path dataRoot, Path repositoryFile, Path managedRoot) {
    public ForgePaths {
        dataRoot = dataRoot.toAbsolutePath().normalize();
        repositoryFile = repositoryFile.toAbsolutePath().normalize();
        managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public static ForgePaths at(Path dataRoot) {
        var root = dataRoot.toAbsolutePath().normalize();
        return new ForgePaths(root, root.resolve("forge.db"), root.resolve("managed"));
    }

    public static ForgePaths defaults() {
        var localAppData = System.getenv("LOCALAPPDATA");
        Path root;
        if (localAppData != null && !localAppData.isBlank()) {
            root = Path.of(localAppData, "PathLab Forge");
        } else {
            root = Path.of(System.getProperty("user.home"), ".pathlab-forge");
        }
        return at(root);
    }
}
