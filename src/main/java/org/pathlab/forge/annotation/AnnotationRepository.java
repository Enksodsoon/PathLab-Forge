package org.pathlab.forge.annotation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

public final class AnnotationRepository {
    private static final Set<String> TYPES = Set.of(
            "point", "rectangle", "ellipse", "line", "polygon", "freehand", "text", "measure");
    private final Path managedRoot;

    public AnnotationRepository(Path managedRoot) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public synchronized List<AnnotationRecord> list(String datasetId) throws IOException {
        var properties = read(datasetId);
        var ids = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("annotation.") && key.endsWith(".type"))
                .map(key -> key.substring("annotation.".length(), key.length() - ".type".length()))
                .distinct()
                .toList();
        var result = new ArrayList<AnnotationRecord>();
        for (var id : ids) {
            var prefix = "annotation." + id + ".";
            result.add(new AnnotationRecord(
                    id,
                    properties.getProperty(prefix + "type", ""),
                    properties.getProperty(prefix + "geometry", ""),
                    properties.getProperty(prefix + "label", ""),
                    properties.getProperty(prefix + "color", "#f3b33d"),
                    Long.parseLong(properties.getProperty(prefix + "createdAt", "0"))));
        }
        result.sort(Comparator.comparingLong(AnnotationRecord::createdAt));
        return List.copyOf(result);
    }

    public synchronized AnnotationRecord create(
            String datasetId, String type, String geometry, String label, String color)
            throws IOException {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported annotation tool");
        }
        if (geometry == null
                || geometry.isBlank()
                || geometry.length() > 65_536
                || !geometry.matches("-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?(?:;-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?)*")) {
            throw new IllegalArgumentException("Annotation geometry is invalid");
        }
        if (label == null || label.length() > 240) {
            throw new IllegalArgumentException("Annotation label is too long");
        }
        if (color == null || !color.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Annotation color is invalid");
        }
        var record = new AnnotationRecord(
                UUID.randomUUID().toString(),
                type,
                geometry,
                label,
                color.toLowerCase(java.util.Locale.ROOT),
                System.currentTimeMillis());
        var properties = read(datasetId);
        var prefix = "annotation." + record.id() + ".";
        properties.setProperty(prefix + "type", record.type());
        properties.setProperty(prefix + "geometry", record.geometry());
        properties.setProperty(prefix + "label", record.label());
        properties.setProperty(prefix + "color", record.color());
        properties.setProperty(prefix + "createdAt", Long.toString(record.createdAt()));
        write(datasetId, properties);
        return record;
    }

    public synchronized boolean delete(String datasetId, String annotationId) throws IOException {
        var properties = read(datasetId);
        var prefix = "annotation." + annotationId + ".";
        var keys = properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .toList();
        keys.forEach(properties::remove);
        var changed = !keys.isEmpty();
        if (changed) {
            write(datasetId, properties);
        }
        return changed;
    }

    private Properties read(String datasetId) throws IOException {
        var file = file(datasetId);
        var properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (var input = Files.newInputStream(file)) {
                properties.load(input);
            }
        }
        return properties;
    }

    private void write(String datasetId, Properties properties) throws IOException {
        var file = file(datasetId);
        Files.createDirectories(file.getParent());
        var partial = file.resolveSibling("annotations.properties.partial");
        try (var output = Files.newOutputStream(partial)) {
            properties.store(output, "PathLab Forge annotations");
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

    private Path file(String datasetId) {
        if (!datasetId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Invalid dataset identifier");
        }
        var file = managedRoot.resolve(datasetId).resolve("annotations.properties").normalize();
        if (!file.startsWith(managedRoot)) {
            throw new IllegalArgumentException("Dataset identifier escapes managed storage");
        }
        return file;
    }
}
