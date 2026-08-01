package org.pathlab.forge.pivot;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.pathlab.forge.library.LocalDataset;

public final class PivotRepository {
    private static final String MANIFEST_FILE = "manifest.properties";
    private static final String CURRENT_FILE = "current.txt";
    private static final String SESSION_FILE = "active-session.properties";
    private final Path managedRoot;

    public PivotRepository(Path managedRoot) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public Optional<PivotManifest> findCurrent(LocalDataset dataset) throws IOException {
        var current = currentId(dataset.id());
        if (current.isEmpty()) {
            return Optional.empty();
        }
        var manifestFile = runRoot(dataset.id(), current.orElseThrow()).resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        var manifest = readManifest(manifestFile);
        if (!manifest.datasetId().equals(dataset.id())
                || !manifest.sourceFingerprint().equals(dataset.sourceFingerprint())
                || !manifest.inputRevision().equals(PivotCompiler.expectedInputRevision(dataset))
                || manifest.selectedSeries() != dataset.selectedSeries()
                || manifest.sourceWidth() != dataset.width()
                || manifest.sourceHeight() != dataset.height()) {
            return Optional.empty();
        }
        return Optional.of(manifest);
    }

    public PivotManifest save(
            PivotManifest manifest, Map<String, byte[]> queryImages) throws IOException {
        var finalRoot = runRoot(manifest.datasetId(), manifest.id());
        if (!Files.isRegularFile(finalRoot.resolve(MANIFEST_FILE))) {
            var partial = finalRoot.resolveSibling(
                    finalRoot.getFileName() + ".partial-" + UUID.randomUUID());
            try {
                var queries = Files.createDirectories(partial.resolve("queries"));
                for (var task : manifest.tasks()) {
                    var bytes = queryImages.get(task.queryFile());
                    if (bytes == null || bytes.length == 0) {
                        throw new IOException("PIVOT query image is missing: " + task.queryFile());
                    }
                    Files.write(queries.resolve(task.queryFile()), bytes);
                }
                Files.writeString(
                        partial.resolve(MANIFEST_FILE),
                        manifestText(manifest),
                        StandardCharsets.UTF_8);
                Files.createDirectories(finalRoot.getParent());
                move(partial, finalRoot, false);
            } finally {
                deleteTree(partial);
            }
        }
        writeAtomically(
                pivotRoot(manifest.datasetId()).resolve(CURRENT_FILE),
                manifest.id() + System.lineSeparator());
        return readManifest(finalRoot.resolve(MANIFEST_FILE));
    }

    public Path queryImage(String datasetId, PivotTask task) throws IOException {
        var current = currentId(datasetId)
                .orElseThrow(() -> new IOException("PIVOT manifest is unavailable"));
        var target = runRoot(datasetId, current).resolve("queries").resolve(task.queryFile())
                .toAbsolutePath().normalize();
        var root = runRoot(datasetId, current).toAbsolutePath().normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new IOException("PIVOT query image is unavailable");
        }
        return target;
    }

    public void saveSession(PivotSession session) throws IOException {
        writeAtomically(
                runRoot(session.datasetId(), session.manifestId()).resolve(SESSION_FILE),
                sessionText(session));
    }

    public Optional<PivotSession> findSession(LocalDataset dataset, PivotManifest manifest)
            throws IOException {
        var file = runRoot(dataset.id(), manifest.id()).resolve(SESSION_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        var session = readSession(file);
        if (!session.datasetId().equals(dataset.id())
                || !session.manifestId().equals(manifest.id())
                || !session.sourceFingerprint().equals(dataset.sourceFingerprint())) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private Optional<String> currentId(String datasetId) throws IOException {
        var file = pivotRoot(datasetId).resolve(CURRENT_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        var value = Files.readString(file, StandardCharsets.UTF_8).trim();
        return value.matches("[a-f0-9]{16}") ? Optional.of(value) : Optional.empty();
    }

    private Path pivotRoot(String datasetId) {
        if (!datasetId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Dataset identifier is invalid");
        }
        var root = managedRoot.resolve(datasetId).resolve("research").resolve("pivot-v1")
                .toAbsolutePath().normalize();
        if (!root.startsWith(managedRoot)) {
            throw new IllegalArgumentException("PIVOT path escapes managed storage");
        }
        return root;
    }

    private Path runRoot(String datasetId, String manifestId) {
        if (!manifestId.matches("[a-f0-9]{16}")) {
            throw new IllegalArgumentException("PIVOT manifest identifier is invalid");
        }
        return pivotRoot(datasetId).resolve("runs").resolve(manifestId);
    }

    private static void writeAtomically(Path target, String value) throws IOException {
        Files.createDirectories(target.getParent());
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.writeString(partial, value, StandardCharsets.UTF_8);
        move(partial, target, true);
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        var options = replace
                ? new StandardCopyOption[] {
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                }
                : new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE};
        try {
            Files.move(source, target, options);
        } catch (AtomicMoveNotSupportedException ignored) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String manifestText(PivotManifest manifest) {
        var values = new HashMap<String, String>();
        values.put("schema", manifest.schema());
        values.put("algorithmVersion", manifest.algorithmVersion());
        values.put("id", manifest.id());
        values.put("datasetId", manifest.datasetId());
        values.put("sourceFingerprint", manifest.sourceFingerprint());
        values.put("inputRevision", manifest.inputRevision());
        values.put("selectedSeries", Integer.toString(manifest.selectedSeries()));
        values.put("previewWidth", Integer.toString(manifest.previewWidth()));
        values.put("previewHeight", Integer.toString(manifest.previewHeight()));
        values.put("sourceWidth", Integer.toString(manifest.sourceWidth()));
        values.put("sourceHeight", Integer.toString(manifest.sourceHeight()));
        values.put("seed", Long.toString(manifest.seed()));
        values.put("createdAt", Long.toString(manifest.createdAt()));
        values.put("generationMs", Long.toString(manifest.generationMs()));
        values.put("inspectedCandidates", Integer.toString(manifest.inspectedCandidates()));
        values.put("rejectedBlank", Integer.toString(manifest.rejectedBlank()));
        values.put("rejectedMissing", Integer.toString(manifest.rejectedMissing()));
        values.put("taskCount", Integer.toString(manifest.tasks().size()));
        for (var index = 0; index < manifest.tasks().size(); index++) {
            var task = manifest.tasks().get(index);
            var prefix = "task." + index + ".";
            values.put(prefix + "id", task.id());
            values.put(prefix + "queryFile", task.queryFile());
            values.put(prefix + "targetX", Double.toString(task.targetX()));
            values.put(prefix + "targetY", Double.toString(task.targetY()));
            values.put(prefix + "targetWidth", Double.toString(task.targetWidth()));
            values.put(prefix + "targetHeight", Double.toString(task.targetHeight()));
            values.put(prefix + "previewLevel", Integer.toString(task.previewLevel()));
            values.put(prefix + "tileX", Integer.toString(task.tileX()));
            values.put(prefix + "tileY", Integer.toString(task.tileY()));
            values.put(prefix + "scaleGap", Integer.toString(task.scaleGap()));
            values.put(prefix + "tissueFraction", Double.toString(task.tissueFraction()));
            values.put(prefix + "ambiguity", Double.toString(task.ambiguity()));
            values.put(prefix + "difficulty", Double.toString(task.difficulty()));
        }
        return sortedText(values);
    }

    private static PivotManifest readManifest(Path file) throws IOException {
        var values = properties(file);
        var tasks = new ArrayList<PivotTask>();
        for (var index = 0; index < integer(values, "taskCount"); index++) {
            var prefix = "task." + index + ".";
            tasks.add(new PivotTask(
                    required(values, prefix + "id"),
                    required(values, prefix + "queryFile"),
                    decimal(values, prefix + "targetX"),
                    decimal(values, prefix + "targetY"),
                    decimal(values, prefix + "targetWidth"),
                    decimal(values, prefix + "targetHeight"),
                    integer(values, prefix + "previewLevel"),
                    integer(values, prefix + "tileX"),
                    integer(values, prefix + "tileY"),
                    integer(values, prefix + "scaleGap"),
                    decimal(values, prefix + "tissueFraction"),
                    decimal(values, prefix + "ambiguity"),
                    decimal(values, prefix + "difficulty")));
        }
        return new PivotManifest(
                required(values, "schema"),
                required(values, "algorithmVersion"),
                required(values, "id"),
                required(values, "datasetId"),
                required(values, "sourceFingerprint"),
                required(values, "inputRevision"),
                integer(values, "selectedSeries"),
                integer(values, "previewWidth"),
                integer(values, "previewHeight"),
                integer(values, "sourceWidth"),
                integer(values, "sourceHeight"),
                number(values, "seed"),
                number(values, "createdAt"),
                number(values, "generationMs"),
                integer(values, "inspectedCandidates"),
                integer(values, "rejectedBlank"),
                integer(values, "rejectedMissing"),
                tasks);
    }

    private static String sessionText(PivotSession session) {
        var values = new HashMap<String, String>();
        values.put("id", session.id());
        values.put("datasetId", session.datasetId());
        values.put("manifestId", session.manifestId());
        values.put("sourceFingerprint", session.sourceFingerprint());
        values.put("state", session.state().name());
        values.put("startedAt", Long.toString(session.startedAt()));
        values.put("updatedAt", Long.toString(session.updatedAt()));
        values.put("currentTaskId", session.currentTaskId());
        values.put("completedTasks", Integer.toString(session.completedTasks()));
        values.put("skippedTasks", Integer.toString(session.skippedTasks()));
        values.put("hintsUsed", Integer.toString(session.hintsUsed()));
        values.put("skippedTaskIds", String.join(",", session.skippedTaskIds()));
        values.put("attemptCount", Integer.toString(session.attempts().size()));
        for (var index = 0; index < session.attempts().size(); index++) {
            var attempt = session.attempts().get(index);
            var prefix = "attempt." + index + ".";
            values.put(prefix + "taskId", attempt.taskId());
            values.put(prefix + "submittedX", Double.toString(attempt.submittedX()));
            values.put(prefix + "submittedY", Double.toString(attempt.submittedY()));
            values.put(prefix + "elapsedMs", Long.toString(attempt.elapsedMs()));
            values.put(prefix + "panDistance", Double.toString(attempt.panDistance()));
            values.put(prefix + "zoomReversals", Integer.toString(attempt.zoomReversals()));
            values.put(prefix + "confidence", Integer.toString(attempt.confidence()));
            values.put(prefix + "normalizedError", Double.toString(attempt.normalizedError()));
            values.put(prefix + "rating", attempt.rating());
            values.put(prefix + "completedAt", Long.toString(attempt.completedAt()));
        }
        return sortedText(values);
    }

    private static PivotSession readSession(Path file) throws IOException {
        var values = properties(file);
        var attempts = new ArrayList<PivotAttempt>();
        for (var index = 0; index < integer(values, "attemptCount"); index++) {
            var prefix = "attempt." + index + ".";
            attempts.add(new PivotAttempt(
                    required(values, prefix + "taskId"),
                    decimal(values, prefix + "submittedX"),
                    decimal(values, prefix + "submittedY"),
                    number(values, prefix + "elapsedMs"),
                    decimal(values, prefix + "panDistance"),
                    integer(values, prefix + "zoomReversals"),
                    integer(values, prefix + "confidence"),
                    decimal(values, prefix + "normalizedError"),
                    required(values, prefix + "rating"),
                    number(values, prefix + "completedAt")));
        }
        var skippedValue = values.getProperty("skippedTaskIds", "");
        var skipped = skippedValue.isBlank() ? List.<String>of() : List.of(skippedValue.split(","));
        return new PivotSession(
                required(values, "id"),
                required(values, "datasetId"),
                required(values, "manifestId"),
                required(values, "sourceFingerprint"),
                PivotSessionState.valueOf(required(values, "state")),
                number(values, "startedAt"),
                number(values, "updatedAt"),
                values.getProperty("currentTaskId", ""),
                integer(values, "completedTasks"),
                integer(values, "skippedTasks"),
                integer(values, "hintsUsed"),
                skipped,
                attempts);
    }

    private static Properties properties(Path file) throws IOException {
        var properties = new Properties();
        try (var reader = new StringReader(Files.readString(file, StandardCharsets.UTF_8))) {
            properties.load(reader);
        }
        return properties;
    }

    private static String sortedText(Map<String, String> values) {
        var text = new StringBuilder();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> text
                .append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
        return text.toString();
    }

    private static String required(Properties values, String name) {
        var value = values.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing PIVOT property: " + name);
        }
        return value;
    }

    private static int integer(Properties values, String name) {
        return Integer.parseInt(required(values, name));
    }

    private static long number(Properties values, String name) {
        return Long.parseLong(required(values, name));
    }

    private static double decimal(Properties values, String name) {
        return Double.parseDouble(required(values, name));
    }
}
