package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;

/** Installs a verified external runtime without ever modifying its source directory. */
public final class ReaderRuntimeInstaller {
    private final Path runtimeRoot;
    private final Path versionsRoot;

    public ReaderRuntimeInstaller(Path dataRoot) {
        runtimeRoot = dataRoot.toAbsolutePath().normalize().resolve("runtime");
        versionsRoot = runtimeRoot.resolve("readers");
    }

    public synchronized Path install(Path source, ReaderRuntimeManifest manifest) throws IOException {
        if (!ReaderRuntimeManifest.currentPlatform().equals(manifest.platform())) {
            throw new IOException("Reader runtime platform does not match this host");
        }
        manifest.verify(source, false);
        Files.createDirectories(versionsRoot);
        cleanupIncomplete();
        var target = versionsRoot.resolve(manifest.fingerprint()).normalize();
        requireVersionTarget(target);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            var staging = versionsRoot.resolve("." + manifest.fingerprint() + ".staging").normalize();
            requireVersionTarget(staging);
            deleteTree(staging);
            Files.createDirectories(staging);
            try {
                for (var entry : manifest.files()) {
                    var sourceFile = source.toAbsolutePath().normalize()
                            .resolve(entry.path().replace('/', java.io.File.separatorChar)).normalize();
                    var targetFile = staging.resolve(
                            entry.path().replace('/', java.io.File.separatorChar)).normalize();
                    if (!sourceFile.startsWith(source.toAbsolutePath().normalize())
                            || !targetFile.startsWith(staging)) throw new IOException("Runtime path escaped staging");
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(sourceFile, targetFile, StandardCopyOption.COPY_ATTRIBUTES);
                }
                Files.writeString(staging.resolve("reader-runtime-manifest.json"), manifest.toJson(),
                        StandardCharsets.UTF_8);
                if ("NON_REDISTRIBUTABLE".equals(manifest.distributionLabel())) {
                    Files.writeString(staging.resolve("NON_REDISTRIBUTABLE"),
                            "Internal validation only. Redistribution is not authorized.\n",
                            StandardCharsets.UTF_8);
                }
                manifest.verify(staging, false);
                move(staging, target);
            } catch (IOException | RuntimeException error) {
                deleteTree(staging);
                throw error;
            }
        } else {
            ReaderRuntimeManifest.read(target.resolve("reader-runtime-manifest.json"))
                    .verify(target, false);
        }
        var current = activeFingerprint();
        if (current.isPresent() && !current.get().equals(manifest.fingerprint())) {
            writePointer("readers-previous.txt", current.get());
        }
        writePointer("readers-current.txt", manifest.fingerprint());
        return target;
    }

    public synchronized Optional<String> activeFingerprint() {
        return readPointer("readers-current.txt");
    }

    public synchronized void rollback() throws IOException {
        var previous = readPointer("readers-previous.txt")
                .orElseThrow(() -> new IOException("No previous reader runtime is available"));
        var target = versionsRoot.resolve(previous).normalize();
        requireVersionTarget(target);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Previous reader runtime is missing");
        }
        ReaderRuntimeManifest.read(target.resolve("reader-runtime-manifest.json")).verify(target, false);
        writePointer("readers-current.txt", previous);
        Files.deleteIfExists(runtimeRoot.resolve("readers-previous.txt"));
    }

    public synchronized void uninstall(String fingerprint) throws IOException {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IOException("Reader runtime fingerprint is invalid");
        }
        if (activeFingerprint().filter(fingerprint::equals).isPresent()) {
            Files.deleteIfExists(runtimeRoot.resolve("readers-current.txt"));
        }
        if (readPointer("readers-previous.txt").filter(fingerprint::equals).isPresent()) {
            Files.deleteIfExists(runtimeRoot.resolve("readers-previous.txt"));
        }
        var target = versionsRoot.resolve(fingerprint).normalize();
        requireVersionTarget(target);
        deleteTree(target);
    }

    public synchronized void cleanupIncomplete() throws IOException {
        if (!Files.isDirectory(versionsRoot, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.list(versionsRoot)) {
            for (var path : paths.filter(item -> item.getFileName().toString().startsWith(".")
                    && item.getFileName().toString().endsWith(".staging")).toList()) {
                deleteTree(path);
            }
        }
    }

    private Optional<String> readPointer(String name) {
        var pointer = runtimeRoot.resolve(name);
        try {
            if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            var value = Files.readString(pointer).trim().toLowerCase(java.util.Locale.ROOT);
            return value.matches("[0-9a-f]{64}") ? Optional.of(value) : Optional.empty();
        } catch (IOException ignored) { return Optional.empty(); }
    }

    private void writePointer(String name, String value) throws IOException {
        Files.createDirectories(runtimeRoot);
        var pointer = runtimeRoot.resolve(name);
        var partial = runtimeRoot.resolve(name + ".partial");
        Files.writeString(partial, value + "\n", StandardCharsets.UTF_8);
        move(partial, pointer);
    }

    private void requireVersionTarget(Path path) throws IOException {
        if (!path.startsWith(versionsRoot.normalize()) || path.equals(versionsRoot.normalize())) {
            throw new IOException("Reader runtime target escaped managed storage");
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
