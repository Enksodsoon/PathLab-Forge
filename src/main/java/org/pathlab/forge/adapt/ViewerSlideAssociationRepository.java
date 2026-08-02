package org.pathlab.forge.adapt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class ViewerSlideAssociationRepository {
    private final Path root;

    public ViewerSlideAssociationRepository(Path managedRoot) {
        root = managedRoot.toAbsolutePath().normalize()
                .resolve("research").resolve("adapt-v1").resolve("viewer-slides");
    }

    public synchronized ViewerSlideAssociation record(ViewerSlideAssociation association) throws IOException {
        var target = target(association.datasetId());
        var values = new Properties();
        values.setProperty("datasetId", association.datasetId());
        values.setProperty("viewerSlideId", association.viewerSlideId());
        values.setProperty("sha256", association.sha256());
        values.setProperty("displayName", association.displayName());
        values.setProperty("license", association.license());
        values.setProperty("artifactRevisionId", association.artifactRevisionId());
        write(target, values);
        return association;
    }

    public ViewerSlideAssociation require(String datasetId, String viewerSlideId) throws IOException {
        var association = read(target(datasetId));
        if (!association.viewerSlideId().equals(viewerSlideId)) {
            throw new IllegalArgumentException("Viewer slide is not associated with this dataset");
        }
        return association;
    }

    public List<ViewerSlideAssociation> list() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        var result = new ArrayList<ViewerSlideAssociation>();
        try (var paths = Files.list(root)) {
            for (var path : paths.filter(value -> value.getFileName().toString().endsWith(".properties")).toList()) {
                result.add(read(path));
            }
        }
        return List.copyOf(result);
    }

    private ViewerSlideAssociation read(Path target) throws IOException {
        if (!Files.isRegularFile(target)) throw new IllegalArgumentException("Dataset has no persisted Viewer slide association");
        var values = new Properties();
        try (var reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) { values.load(reader); }
        return new ViewerSlideAssociation(values.getProperty("datasetId"), values.getProperty("viewerSlideId"),
                values.getProperty("sha256"), values.getProperty("displayName"), values.getProperty("license"),
                values.getProperty("artifactRevisionId"));
    }

    private Path target(String datasetId) {
        return root.resolve(hash(datasetId) + ".properties").toAbsolutePath().normalize();
    }

    private static void write(Path target, Properties values) throws IOException {
        Files.createDirectories(target.getParent());
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        try (var writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) { values.store(writer, null); }
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
