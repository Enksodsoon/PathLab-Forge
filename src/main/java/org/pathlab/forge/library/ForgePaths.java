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

    /** Copies legacy data once; the original remains available for recovery. */
    public static void migrateLegacyMacData() throws java.io.IOException {
        if (!System.getProperty("os.name", "").startsWith("Mac")) return;
        migrateLegacyData(Path.of(System.getProperty("user.home"), ".pathlab-forge"), defaults().dataRoot());
    }

    @SuppressWarnings("try")
    static void migrateLegacyData(Path legacy, Path target) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(legacy, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || containsData(target)) return;
        var staging = target.resolveSibling(target.getFileName() + ".migration.partial");
        var lockRoot = target.resolveSibling(target.getFileName() + ".migration-lock");
        try (var migrationLock = org.pathlab.forge.runtime.DataRootLock.acquire(lockRoot);
                var legacyLock = org.pathlab.forge.runtime.DataRootLock.acquire(legacy)) {
            if (containsData(target)) return;
            if (java.nio.file.Files.exists(staging, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new java.io.IOException("Incomplete Forge migration requires recovery: " + staging);
            java.nio.file.Files.createDirectories(staging);
            try (var paths = java.nio.file.Files.walk(legacy)) {
                for (var source : paths.toList()) {
                    if (source.equals(legacy) || source.equals(legacy.resolve("forge.lock"))) continue;
                    if (java.nio.file.Files.isSymbolicLink(source))
                        throw new java.io.IOException("Legacy Forge data contains a symbolic link; migration stopped");
                    var destination = staging.resolve(legacy.relativize(source));
                    if (java.nio.file.Files.isDirectory(source)) java.nio.file.Files.createDirectories(destination);
                    else java.nio.file.Files.copy(source, destination, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
            // Deleting only an empty directory fails safely if another owner created data.
            if (java.nio.file.Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                java.nio.file.Files.delete(target);
            java.nio.file.Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static boolean containsData(Path target) throws java.io.IOException {
        if (!java.nio.file.Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        if (!java.nio.file.Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true;
        try (var children = java.nio.file.Files.list(target)) { return children.findAny().isPresent(); }
    }

    public static ForgePaths defaults() {
        var localAppData = System.getenv("LOCALAPPDATA");
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            return at(Path.of(System.getProperty("user.home"), "Library", "Application Support", "PathLab Forge"));
        }
        Path root;
        if (localAppData != null && !localAppData.isBlank()) {
            root = Path.of(localAppData, "PathLab Forge");
        } else {
            root = Path.of(System.getProperty("user.home"), ".pathlab-forge");
        }
        return at(root);
    }
}
