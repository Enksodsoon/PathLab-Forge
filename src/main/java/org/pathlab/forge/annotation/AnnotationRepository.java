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
            "point",
            "ruler",
            "polyline",
            "angle",
            "rectangle",
            "ellipse",
            "polygon",
            "freehand",
            "brush_add",
            "brush_subtract",
            "text",
            "line",
            "measure");
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
                    Long.parseLong(properties.getProperty(prefix + "createdAt", "0")),
                    properties.getProperty(prefix + "parentId", ""),
                    properties.getProperty(prefix + "classification", ""),
                    Long.parseLong(properties.getProperty(
                            prefix + "updatedAt", properties.getProperty(prefix + "createdAt", "0"))),
                    Long.parseLong(properties.getProperty(prefix + "revision", "1")),
                    Integer.parseInt(properties.getProperty(prefix + "series", "-1")),
                    Integer.parseInt(properties.getProperty(prefix + "z", "-1")),
                    Integer.parseInt(properties.getProperty(prefix + "t", "-1")),
                    properties.getProperty(prefix + "viewRevision", "")));
        }
        result.sort(Comparator.comparingLong(AnnotationRecord::createdAt));
        return List.copyOf(result);
    }

    public synchronized AnnotationRecord create(
            String datasetId, String type, String geometry, String label, String color)
            throws IOException {
        return create(datasetId, type, geometry, label, color, -1, -1, -1, "");
    }

    public synchronized AnnotationRecord create(
            String datasetId, String type, String geometry, String label, String color,
            int series, int z, int t, String viewRevision)
            throws IOException {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported annotation tool");
        }
        org.pathlab.forge.analysis.GeometryMeasurements.validate(type, geometry);
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
                System.currentTimeMillis(), "", "", System.currentTimeMillis(), 1,
                series, z, t, viewRevision);
        var properties = read(datasetId);
        var prefix = "annotation." + record.id() + ".";
        properties.setProperty(prefix + "type", record.type());
        properties.setProperty(prefix + "geometry", record.geometry());
        properties.setProperty(prefix + "label", record.label());
        properties.setProperty(prefix + "color", record.color());
        properties.setProperty(prefix + "createdAt", Long.toString(record.createdAt()));
        properties.setProperty(prefix + "updatedAt", Long.toString(record.updatedAt()));
        properties.setProperty(prefix + "revision", Long.toString(record.revision()));
        writeScope(properties, prefix, record);
        write(datasetId, properties);
        return record;
    }

    public synchronized void scopeLegacy(
            String datasetId, int series, int z, int t, String viewRevision) throws IOException {
        // Legacy coordinates have no evidence of series/Z/T. Preserve them unscoped;
        // operators must redraw on a verified view before using them for analysis.
        file(datasetId);
    }

    /** Atomically persist reviewed, present TMA cores; never overwrite operator edits. */
    public synchronized List<AnnotationRecord> saveTmaCores(
            String datasetId, String parentId, long expectedParentRevision,
            String runId, long reviewRevision, List<org.pathlab.forge.analysis.PathObject> cores)
            throws IOException {
        var parent = list(datasetId).stream().filter(item -> item.id().equals(parentId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("TMA parent was not found"));
        if (parent.revision() != expectedParentRevision) throw new IllegalStateException("TMA parent revision changed");
        if (parent.series() < 0 || parent.z() < 0 || parent.t() < 0 || parent.viewRevision().isBlank()
                || reviewRevision < 1 || !runId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("TMA requires an explicitly saved review and exact view scope");
        }
        if (cores.size() > 10_000) throw new IllegalArgumentException("TMA grid exceeds 100 by 100 cores");
        var bounds = new org.pathlab.forge.analysis.RoiMask(parent.type(), parent.geometry()).bounds();
        var properties = read(datasetId);
        var ids = new java.util.HashSet<String>();
        for (var core : cores) {
            if (!ids.add(core.id()) || !core.id().matches("[0-9a-fA-F-]{36}")
                    || !core.datasetId().equals(datasetId) || !core.parentId().equals(parentId)
                    || !core.sourceRunId().equals(runId) || core.kind() != org.pathlab.forge.analysis.PathObject.Kind.TMA_CORE
                    || core.classification().length() > 120
                    || !Set.of("true", "false").contains(core.properties().getOrDefault("missing", ""))) throw new IllegalArgumentException("TMA core identity is invalid");
            org.pathlab.forge.analysis.GeometryMeasurements.validate("rectangle", core.geometry());
            for (var coordinates : core.geometry().split(";")) {
                var point = coordinates.split(","); var x = Double.parseDouble(point[0]); var y = Double.parseDouble(point[1]);
                if (x < bounds.x() || y < bounds.y() || x > (long) bounds.x() + bounds.width()
                        || y > (long) bounds.y() + bounds.height()) throw new IllegalArgumentException("TMA core extends outside its reviewed grid");
            }
            var prefix = "annotation." + core.id() + ".";
            if (properties.containsKey(prefix + "type")) {
                if (!properties.getProperty(prefix + "sourceRunId", "").equals(runId)
                        || !properties.getProperty(prefix + "reviewRevision", "").equals(Long.toString(reviewRevision))
                        || !properties.getProperty(prefix + "objectRevision", "").equals(Long.toString(core.revision()))
                        || !properties.getProperty(prefix + "geometry", "").equals(core.geometry())
                        || !properties.getProperty(prefix + "parentId", "").equals(parentId)
                        || !properties.getProperty(prefix + "classification", "").equals(core.classification())) {
                    throw new IllegalStateException("Persisted TMA core changed; preserve it and create a new reviewed grid");
                }
                continue;
            }
            if ("true".equals(core.properties().get("missing"))) continue;
            var now = System.currentTimeMillis();
            var record = new AnnotationRecord(core.id(), "rectangle", core.geometry(), core.classification(),
                    parent.color(), now, parentId, core.classification(), now, 1,
                    parent.series(), parent.z(), parent.t(), parent.viewRevision());
            properties.setProperty(prefix + "type", record.type());
            properties.setProperty(prefix + "geometry", record.geometry());
            properties.setProperty(prefix + "label", record.label());
            properties.setProperty(prefix + "color", record.color());
            properties.setProperty(prefix + "createdAt", Long.toString(now));
            properties.setProperty(prefix + "updatedAt", Long.toString(now));
            properties.setProperty(prefix + "revision", "1");
            properties.setProperty(prefix + "parentId", parentId);
            properties.setProperty(prefix + "classification", core.classification());
            properties.setProperty(prefix + "sourceRunId", runId);
            properties.setProperty(prefix + "reviewRevision", Long.toString(reviewRevision));
            properties.setProperty(prefix + "objectRevision", Long.toString(core.revision()));
            writeScope(properties, prefix, record);
        }
        write(datasetId, properties);
        return list(datasetId).stream().filter(item -> ids.contains(item.id())
                && cores.stream().anyMatch(core -> core.id().equals(item.id()) && !"true".equals(core.properties().get("missing")))).toList();
    }

    public synchronized java.util.Map<String, String> derivedProvenance(String datasetId, String annotationId)
            throws IOException {
        var properties = read(datasetId); var prefix = "annotation." + annotationId + ".";
        return java.util.Map.of("sourceRunId", properties.getProperty(prefix + "sourceRunId", ""),
                "reviewRevision", properties.getProperty(prefix + "reviewRevision", ""),
                "objectRevision", properties.getProperty(prefix + "objectRevision", ""));
    }

    public synchronized AnnotationRecord updateMetadata(
            String datasetId,
            String annotationId,
            String parentId,
            String classification,
            long expectedRevision)
            throws IOException {
        if (parentId == null || parentId.length() > 64
                || (!parentId.isBlank() && !parentId.matches("[0-9a-fA-F-]{36}"))) {
            throw new IllegalArgumentException("Annotation parent is invalid");
        }
        if (classification == null || classification.length() > 120) {
            throw new IllegalArgumentException("Annotation classification is too long");
        }
        var current = list(datasetId).stream()
                .filter(annotation -> annotation.id().equals(annotationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Annotation was not found"));
        if (current.revision() != expectedRevision) {
            throw new IllegalStateException("Annotation revision changed");
        }
        if (!parentId.isBlank() && parentId.equals(annotationId)) {
            throw new IllegalArgumentException("Annotation cannot be its own parent");
        }
        var records = list(datasetId);
        var visited = new java.util.HashSet<String>();
        var ancestorId = parentId;
        while (!ancestorId.isBlank()) {
            if (ancestorId.equals(annotationId) || !visited.add(ancestorId)) {
                throw new IllegalArgumentException("Annotation parent creates a cycle");
            }
            var nextId = ancestorId;
            var ancestor = records.stream().filter(item -> item.id().equals(nextId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Annotation parent was not found"));
            if (ancestor.series() != current.series() || ancestor.z() != current.z()
                    || ancestor.t() != current.t() || !ancestor.viewRevision().equals(current.viewRevision())) {
                throw new IllegalArgumentException("Annotation parent belongs to another view");
            }
            ancestorId = ancestor.parentId();
        }
        var updated = new AnnotationRecord(
                current.id(), current.type(), current.geometry(), current.label(), current.color(),
                current.createdAt(), parentId, classification.strip(), System.currentTimeMillis(),
                current.revision() + 1, current.series(), current.z(), current.t(),
                current.viewRevision());
        var properties = read(datasetId);
        var prefix = "annotation." + annotationId + ".";
        properties.setProperty(prefix + "parentId", updated.parentId());
        properties.setProperty(prefix + "classification", updated.classification());
        properties.setProperty(prefix + "updatedAt", Long.toString(updated.updatedAt()));
        properties.setProperty(prefix + "revision", Long.toString(updated.revision()));
        write(datasetId, properties);
        return updated;
    }

    private static void writeScope(
            Properties properties, String prefix, AnnotationRecord record) {
        properties.setProperty(prefix + "series", Integer.toString(record.series()));
        properties.setProperty(prefix + "z", Integer.toString(record.z()));
        properties.setProperty(prefix + "t", Integer.toString(record.t()));
        properties.setProperty(prefix + "viewRevision", record.viewRevision());
    }

    public synchronized AnnotationRecord updateGeometry(
            String datasetId, String annotationId, String geometry, String label, String color,
            long expectedRevision) throws IOException {
        var current = list(datasetId).stream().filter(item -> item.id().equals(annotationId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Annotation was not found"));
        if (current.revision() != expectedRevision) throw new IllegalStateException("Annotation revision changed");
        org.pathlab.forge.analysis.GeometryMeasurements.validate(current.type(), geometry);
        if (label == null || label.length() > 240) throw new IllegalArgumentException("Annotation label is too long");
        if (color == null || !color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Annotation color is invalid");
        var properties = read(datasetId);
        var prefix = "annotation." + annotationId + ".";
        properties.setProperty(prefix + "geometry", geometry);
        properties.setProperty(prefix + "label", label);
        properties.setProperty(prefix + "color", color.toLowerCase(java.util.Locale.ROOT));
        properties.setProperty(prefix + "updatedAt", Long.toString(System.currentTimeMillis()));
        properties.setProperty(prefix + "revision", Long.toString(current.revision() + 1));
        write(datasetId, properties);
        return list(datasetId).stream().filter(item -> item.id().equals(annotationId)).findFirst().orElseThrow();
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
            // Preserve descendants, reparenting direct children to the deleted object's parent.
            var deleted = list(datasetId).stream().filter(item -> item.id().equals(annotationId)).findFirst().orElseThrow();
            for (var child : list(datasetId)) {
                if (!child.parentId().equals(annotationId)) continue;
                var childPrefix = "annotation." + child.id() + ".";
                properties.setProperty(childPrefix + "parentId", deleted.parentId());
                properties.setProperty(childPrefix + "revision", Long.toString(child.revision() + 1));
                properties.setProperty(childPrefix + "updatedAt", Long.toString(System.currentTimeMillis()));
            }
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
