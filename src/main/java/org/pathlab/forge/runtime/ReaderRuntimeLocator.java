package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ReaderRuntimeLocator {
    private ReaderRuntimeLocator() {}

    public static Optional<Path> activeRoot(Path dataRoot) {
        for (var candidate : candidateDataRoots(dataRoot)) {
            var found = activeRootAt(candidate);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    static List<Path> candidateDataRoots(Path dataRoot) {
        var roots = new ArrayList<Path>();
        var installed = System.getProperty("pathlab.forge.readerDataRoot", "").trim();
        if (!installed.isEmpty()) roots.add(Path.of(installed).toAbsolutePath().normalize());
        if (Boolean.getBoolean("pathlab.forge.runtime.requireProduction")) return List.copyOf(roots);
        roots.add(dataRoot.toAbsolutePath().normalize());
        var appPath = System.getProperty("jpackage.app-path", "").trim();
        if (!appPath.isEmpty()) {
            var parent = Path.of(appPath).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                roots.add(parent.resolve("reader-data"));
                if (parent.getFileName().toString().equals("MacOS") && parent.getParent() != null)
                    roots.add(parent.getParent().resolve("Resources/reader-data"));
            }
        }
        return List.copyOf(roots);
    }

    private static Optional<Path> activeRootAt(Path dataRoot) {
        var runtime = dataRoot.resolve("runtime");
        var pointer = runtime.resolve("readers-current.txt");
        try {
            if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            var fingerprint = Files.readString(pointer).trim().toLowerCase(java.util.Locale.ROOT);
            if (!fingerprint.matches("[0-9a-f]{64}")) return Optional.empty();
            var root = runtime.resolve("readers").resolve(fingerprint).normalize();
            if (!root.startsWith(runtime.resolve("readers").normalize())
                    || !Files.isRegularFile(root.resolve("reader-runtime-manifest.json"),
                            LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            var manifest = ReaderRuntimeManifest.read(root.resolve("reader-runtime-manifest.json"));
            if (!manifest.platform().equals(ReaderRuntimeManifest.currentPlatform())) return Optional.empty();
            manifest.verify(root, Boolean.getBoolean("pathlab.forge.runtime.requireProduction"));
            return Optional.of(root);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Path> componentRoot(Path dataRoot, String componentDirectory) {
        if (componentDirectory == null || !componentDirectory.matches("[a-z0-9._-]{1,32}")) {
            return Optional.empty();
        }
        return activeRoot(dataRoot).map(root -> root.resolve(componentDirectory))
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
    }
}
