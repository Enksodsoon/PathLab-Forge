package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class ArtifactIntegrityStamp {
    private static final String FILE_NAME = "artifact.integrity.properties";

    private ArtifactIntegrityStamp() {}

    public static boolean matches(ArtifactRevision revision) throws IOException {
        var file = stampFile(revision);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        var values = new Properties();
        try (var input = Files.newInputStream(file)) {
            values.load(input);
        }
        try {
            return revision.omeSha256().equals(values.getProperty("omeSha256"))
                    && revision.packageSha256().equals(values.getProperty("packageSha256"))
                    && (!Files.exists(Path.of(revision.omePath()))
                            || unchanged(Path.of(revision.omePath()), values, "ome"))
                    && unchanged(Path.of(revision.packagePath()), values, "package")
                    && unchanged(packageIndex(revision), values, "index");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean matchesOme(ArtifactRevision revision) throws IOException {
        var file = stampFile(revision);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        var values = new Properties();
        try (var input = Files.newInputStream(file)) {
            values.load(input);
        }
        try {
            return revision.omeSha256().equals(values.getProperty("omeSha256"))
                    && revision.omeProfile().equals(values.getProperty("omeProfile", ""))
                    && revision.omeJpegQuality()
                            == Integer.parseInt(values.getProperty("omeJpegQuality", "0"))
                    && unchanged(Path.of(revision.omePath()), values, "ome");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static void write(ArtifactRevision revision) throws IOException {
        var ome = Path.of(revision.omePath());
        var preparedPackage = Path.of(revision.packagePath());
        var index = packageIndex(revision);
        requireFile(preparedPackage);
        requireFile(index);
        var values = new Properties();
        values.setProperty("omeSha256", revision.omeSha256());
        values.setProperty("packageSha256", revision.packageSha256());
        values.setProperty("omeProfile", revision.omeProfile());
        values.setProperty("omeJpegQuality", Integer.toString(revision.omeJpegQuality()));
        if (Files.isRegularFile(ome)) {
            record(values, "ome", ome);
        } else {
            values.setProperty("omeDeletedAfterVerification", "true");
        }
        record(values, "package", preparedPackage);
        record(values, "index", index);
        var file = stampFile(revision);
        var partial = file.resolveSibling(FILE_NAME + ".partial");
        try (var output = Files.newOutputStream(partial)) {
            values.store(output, "PathLab Forge verified immutable artifact identity");
        }
        try {
            Files.move(
                    partial,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean unchanged(Path file, Properties values, String prefix)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        return Files.size(file) == Long.parseLong(values.getProperty(prefix + "Size", "-1"))
                && Files.getLastModifiedTime(file).toMillis()
                        == Long.parseLong(values.getProperty(prefix + "Modified", "-1"));
    }

    private static void record(Properties values, String prefix, Path file) throws IOException {
        values.setProperty(prefix + "Size", Long.toString(Files.size(file)));
        values.setProperty(
                prefix + "Modified",
                Long.toString(Files.getLastModifiedTime(file).toMillis()));
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            throw new IOException("Verified artifact component is missing: " + file);
        }
    }

    private static Path stampFile(ArtifactRevision revision) {
        return Path.of(revision.omePath()).getParent().resolve(FILE_NAME);
    }

    private static Path packageIndex(ArtifactRevision revision) {
        return Path.of(revision.packagePath())
                .resolveSibling(Path.of(revision.packagePath()).getFileName() + ".index");
    }
}
