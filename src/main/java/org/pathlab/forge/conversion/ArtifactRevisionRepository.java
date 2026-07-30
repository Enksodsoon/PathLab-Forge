package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.pathlab.forge.library.LocalDataset;

public final class ArtifactRevisionRepository {
    private final Path managedRoot;

    public ArtifactRevisionRepository(Path managedRoot) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public ArtifactRevision create(LocalDataset dataset, int outputWidth, int outputHeight)
            throws IOException {
        if (dataset.configurationRevision().isBlank()) {
            throw new IllegalStateException("Select an export configuration first");
        }
        var id = UUID.randomUUID().toString();
        var root = revisionRoot(dataset.id(), id);
        Files.createDirectories(root);
        Files.writeString(
                root.resolve(".ultrafast-owned"),
                "PathLab Forge managed payload\n");
        var revision = new ArtifactRevision(
                id,
                dataset.id(),
                dataset.configurationRevision(),
                dataset.sourceFingerprint(),
                System.currentTimeMillis(),
                ArtifactRevisionStatus.CONVERTING,
                root.resolve("export.ome.tif").toString(),
                root.resolve("derivative").toString(),
                root.resolve("slide.plslide").toString(),
                "",
                "",
                outputWidth,
                outputHeight,
                0,
                "");
        save(revision);
        return revision;
    }

    public Optional<ArtifactRevision> find(String datasetId, String revisionId)
            throws IOException {
        var file = revisionRoot(datasetId, revisionId).resolve("revision.properties");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        var properties = new Properties();
        try (var input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return Optional.of(new ArtifactRevision(
                revisionId,
                datasetId,
                properties.getProperty("configurationRevision"),
                properties.getProperty("sourceFingerprint", ""),
                Long.parseLong(properties.getProperty("createdAt")),
                ArtifactRevisionStatus.valueOf(properties.getProperty("status")),
                properties.getProperty("omePath"),
                properties.getProperty("derivativePath"),
                properties.getProperty("packagePath"),
                properties.getProperty("omeSha256", ""),
                properties.getProperty("packageSha256", ""),
                Integer.parseInt(properties.getProperty("outputWidth")),
                Integer.parseInt(properties.getProperty("outputHeight")),
                Long.parseLong(properties.getProperty("approvedAt", "0")),
                properties.getProperty("failure", "")));
    }

    public List<ArtifactRevision> list(String datasetId) throws IOException {
        var root = datasetRoot(datasetId).resolve("artifacts");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        var revisions = new ArrayList<ArtifactRevision>();
        try (var children = Files.list(root)) {
            for (var child : children.filter(Files::isDirectory).toList()) {
                find(datasetId, child.getFileName().toString()).ifPresent(revisions::add);
            }
        }
        revisions.sort(Comparator.comparingLong(ArtifactRevision::createdAt).reversed());
        return List.copyOf(revisions);
    }

    public void save(ArtifactRevision revision) throws IOException {
        var root = revisionRoot(revision.datasetId(), revision.id());
        Files.createDirectories(root);
        var properties = new Properties();
        properties.setProperty("configurationRevision", revision.configurationRevision());
        properties.setProperty("sourceFingerprint", revision.sourceFingerprint());
        properties.setProperty("createdAt", Long.toString(revision.createdAt()));
        properties.setProperty("status", revision.status().name());
        properties.setProperty("omePath", revision.omePath());
        properties.setProperty("derivativePath", revision.derivativePath());
        properties.setProperty("packagePath", revision.packagePath());
        properties.setProperty("omeSha256", revision.omeSha256());
        properties.setProperty("packageSha256", revision.packageSha256());
        properties.setProperty("outputWidth", Integer.toString(revision.outputWidth()));
        properties.setProperty("outputHeight", Integer.toString(revision.outputHeight()));
        properties.setProperty("approvedAt", Long.toString(revision.approvedAt()));
        properties.setProperty("failure", revision.failure());
        var file = root.resolve("revision.properties");
        var partial = root.resolve("revision.properties.partial");
        try (var output = Files.newOutputStream(partial)) {
            properties.store(output, "PathLab Forge immutable artifact revision");
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

    public CleanupReport cleanupSupersededUnapproved(String datasetId, String currentRevisionId)
            throws IOException {
        long deletedFiles = 0;
        long deletedBytes = 0;
        for (var revision : list(datasetId)) {
            if (revision.id().equals(currentRevisionId)
                    || revision.status() == ArtifactRevisionStatus.APPROVED) {
                continue;
            }
            var root = revisionRoot(datasetId, revision.id());
            if (!Files.isRegularFile(root.resolve(".ultrafast-owned"))) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (path.equals(root)
                            || path.equals(root.resolve("revision.properties"))
                            || path.equals(root.resolve(".ultrafast-owned"))) {
                        continue;
                    }
                    if (Files.isRegularFile(path)) {
                        deletedBytes = Math.addExact(deletedBytes, Files.size(path));
                        deletedFiles++;
                        Files.delete(path);
                    } else if (Files.isDirectory(path)) {
                        try (var children = Files.list(path)) {
                            if (children.findAny().isEmpty()) {
                                Files.delete(path);
                            }
                        }
                    }
                }
            }
        }
        return new CleanupReport(deletedFiles, deletedBytes);
    }

    public record CleanupReport(long deletedFiles, long deletedBytes) {
        public CleanupReport {
            if (deletedFiles < 0 || deletedBytes < 0) {
                throw new IllegalArgumentException("Cleanup counts must not be negative");
            }
        }
    }

    private Path revisionRoot(String datasetId, String revisionId) {
        if (!revisionId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Invalid artifact revision identifier");
        }
        var root = datasetRoot(datasetId).resolve("artifacts").resolve(revisionId).normalize();
        if (!root.startsWith(managedRoot)) {
            throw new IllegalArgumentException("Artifact revision escapes managed storage");
        }
        return root;
    }

    private Path datasetRoot(String datasetId) {
        if (!datasetId.matches("[0-9a-fA-F-]{36}|dataset-[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid dataset identifier");
        }
        var root = managedRoot.resolve(datasetId).normalize();
        if (!root.startsWith(managedRoot)) {
            throw new IllegalArgumentException("Dataset identifier escapes managed storage");
        }
        return root;
    }
}
