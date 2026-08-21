package org.pathlab.forge.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.pathlab.forge.viewer.ViewerAuthorizedClient;

public final class StudyPackService {
    public static final int MAX_PACK_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SLIDES = 50;
    public static final int MAX_TASKS = 500;
    public static final String SCHEMA = "pathlab.study-pack/1";
    public static final String PREVIEW_VERSION = "pathlab.study-preview/1";
    private final Path root;
    private final ViewerAuthorizedClient viewer;

    public StudyPackService(Path root, ViewerAuthorizedClient viewer) {
        this.root = root.toAbsolutePath().normalize();
        this.viewer = viewer;
    }

    public Preview preview(String body) throws IOException {
        var definition = parse(body);
        definition.remove("checksum");
        definition.remove("facultyPreview");
        validateCore(definition);
        var checksum = StudyPackCanonicalJson.checksum(definition);
        return new Preview(checksum, StudyPackCanonicalJson.canonicalize(definition));
    }

    public StudyPackRecord save(String body) throws IOException {
        var definition = parse(body);
        validateCore(definition);
        var core = definition.deepCopy();
        core.remove("checksum");
        core.remove("facultyPreview");
        var checksum = StudyPackCanonicalJson.checksum(core);
        if (!checksum.equals(text(definition, "checksum", 64))) {
            throw new IllegalArgumentException("Study Pack checksum changed after preview");
        }
        var preview = object(definition, "facultyPreview");
        if (!checksum.equals(text(preview, "packChecksum", 64))
                || !PREVIEW_VERSION.equals(text(preview, "previewVersion", 80))) {
            throw new IllegalArgumentException("Complete the exact faculty preview before saving");
        }
        var reviewedAt = text(preview, "reviewedAt", 40);
        try {
            Instant.parse(reviewedAt);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Faculty preview timestamp is invalid", error);
        }
        Files.createDirectories(root);
        var target = root.resolve(checksum + ".json");
        var canonical = StudyPackCanonicalJson.canonicalize(definition);
        claimVersion(definition, checksum);
        if (!Files.isRegularFile(target)) {
            var partial = target.resolveSibling(target.getFileName() + ".partial-" + UUID.randomUUID());
            try {
                Files.writeString(partial, canonical, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW);
                atomicMove(partial, target);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
        return record(definition, checksum, reviewedAt, target);
    }

    public List<StudyPackRecord> list() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        var records = new ArrayList<StudyPackRecord>();
        try (var files = Files.list(root)) {
            for (var path : files.filter(item -> item.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList()) {
                var definition = parse(Files.readString(path, StandardCharsets.UTF_8));
                var checksum = path.getFileName().toString().replace(".json", "");
                var preview = object(definition, "facultyPreview");
                records.add(record(definition, checksum, text(preview, "reviewedAt", 40), path));
            }
        }
        records.sort(Comparator.comparing(StudyPackRecord::packKey)
                .thenComparing(StudyPackRecord::version).reversed());
        return List.copyOf(records);
    }

    public String read(String checksum) throws IOException {
        if (!checksum.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid checksum");
        var path = root.resolve(checksum + ".json").normalize();
        if (!path.getParent().equals(root) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Study Pack was not found");
        }
        var body = Files.readString(path, StandardCharsets.UTF_8);
        var definition = parse(body);
        var core = definition.deepCopy();
        core.remove("checksum"); core.remove("facultyPreview");
        if (!checksum.equals(StudyPackCanonicalJson.checksum(core))) {
            throw new IOException("Stored Study Pack checksum does not match");
        }
        return body;
    }

    public String publish(String checksum) throws IOException {
        var body = read(checksum);
        try (var capabilities = viewer.request("GET", "/api/v1/desktop/capabilities", Map.of(), new byte[0])) {
            if (capabilities.status() != 200) throw new IOException("Viewer capability recheck failed");
            var bytes = capabilities.body().readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("Viewer capabilities are too large");
            var rootNode = StudyPackCanonicalJson.mapper().readTree(bytes);
            if (!strings(rootNode, "studyPackSchemas").contains(SCHEMA)
                    || rootNode.path("studyPackMaxBytes").asLong() < body.getBytes(StandardCharsets.UTF_8).length
                    || rootNode.path("studyPackMaxTasks").asInt() < parse(body).path("tasks").size()) {
                throw new IOException("Viewer does not accept this Study Pack contract");
            }
        }
        try (var response = viewer.request(
                "POST", "/api/v1/desktop/study-packs",
                Map.of("Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8))) {
            var bytes = response.body().readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("Viewer response is too large");
            if (response.status() != 200 && response.status() != 201) {
                throw new IOException("Viewer rejected Study Pack (" + response.status() + ")");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void claimVersion(JsonNode definition, String checksum) throws IOException {
        var key = text(definition, "packKey", 120).replaceAll("[^A-Za-z0-9._-]", "_");
        var version = positiveInteger(definition, "version");
        var claim = root.resolve(key + "-v" + version + ".claim");
        try {
            Files.writeString(claim, checksum, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            if (!checksum.equals(Files.readString(claim, StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("Study Pack versions are immutable", error);
            }
        }
    }

    private static ObjectNode parse(String body) throws IOException {
        if (body == null || body.isBlank()
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_PACK_BYTES) {
            throw new IllegalArgumentException("Study Pack is empty or exceeds 2 MiB");
        }
        var value = StudyPackCanonicalJson.mapper().readTree(body);
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("Study Pack must be an object");
        return object;
    }

    private static void validateCore(ObjectNode root) {
        if (!SCHEMA.equals(text(root, "schema", 80))) throw new IllegalArgumentException("Unsupported Study Pack schema");
        var key = text(root, "packKey", 120);
        if (!key.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Pack key is invalid");
        positiveInteger(root, "version");
        text(root, "title", 240); text(root, "author", 240); text(root, "license", 240);
        text(root, "provenance", 1000); text(root, "revision", 120);
        var languages = strings(root, "languages");
        if (languages.isEmpty() || !Set.of("en", "th").containsAll(languages)) {
            throw new IllegalArgumentException("Languages must contain English or Thai");
        }
        var slides = array(root, "slides", 1, MAX_SLIDES);
        var slideIds = new HashSet<String>();
        for (var slide : slides) {
            if (!slide.isObject()) throw new IllegalArgumentException("Slide reference is invalid");
            var id = text(slide, "viewerSlideId", 100);
            if (!slideIds.add(id) || !text(slide, "sha256", 64).matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Slide reference is invalid");
            }
            text(slide, "displayName", 200);
        }
        var tasks = array(root, "tasks", 1, MAX_TASKS);
        var taskIds = new HashSet<String>();
        for (var task : tasks) {
            if (!task.isObject()) throw new IllegalArgumentException("Task is invalid");
            var id = text(task, "id", 120);
            if (!id.matches("[A-Za-z0-9._-]+") || !taskIds.add(id)
                    || !slideIds.contains(text(task, "slideId", 100))) {
                throw new IllegalArgumentException("Task identity is invalid");
            }
            text(task, "prompt", 2000); text(task, "explanation", 8000);
            array(task, "hints", 0, 3).forEach(item -> requireTextNode(item, 2000, "Hint"));
            var sources = array(task, "sources", 1, 10);
            for (var source : sources) {
                text(source, "title", 500);
                if (!text(source, "url", 1000).startsWith("https://")) {
                    throw new IllegalArgumentException("Source URL must use HTTPS");
                }
            }
            var type = text(task, "type", 40);
            if ("multiple-choice".equals(type)) {
                var options = array(task, "options", 2, 10);
                var values = new HashSet<String>();
                for (var option : options) values.add(requireTextNode(option, 1000, "Option"));
                if (values.size() != options.size() || !values.contains(text(task, "answerKey", 1000))) {
                    throw new IllegalArgumentException("Multiple-choice answer key must be explicit");
                }
            } else if ("spatial".equals(type)) {
                var x = unit(task, "targetX", false); var y = unit(task, "targetY", false);
                var width = unit(task, "targetWidth", true); var height = unit(task, "targetHeight", true);
                var tolerance = unit(task, "tolerance", true);
                if (x + width > 1 || y + height > 1 || tolerance > .5) {
                    throw new IllegalArgumentException("Spatial target escapes normalized bounds");
                }
            } else throw new IllegalArgumentException("Task type is unsupported");
        }
    }

    private static JsonNode object(JsonNode parent, String field) {
        var value = parent.get(field);
        if (value == null || !value.isObject()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static List<JsonNode> array(JsonNode parent, String field, int minimum, int maximum) {
        var value = parent.get(field);
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum) {
            throw new IllegalArgumentException(field + " count is invalid");
        }
        var result = new ArrayList<JsonNode>(); value.forEach(result::add); return result;
    }
    private static Set<String> strings(JsonNode parent, String field) {
        var values = new HashSet<String>();
        for (var item : array(parent, field, 1, 20)) values.add(requireTextNode(item, 40, field));
        return values;
    }
    private static String text(JsonNode parent, String field, int maximum) {
        return requireTextNode(parent.get(field), maximum, field);
    }
    private static String requireTextNode(JsonNode value, int maximum, String field) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) throw new IllegalArgumentException(field + " is invalid");
        return value.textValue().strip();
    }
    private static int positiveInteger(JsonNode parent, String field) {
        var value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || value.intValue() < 1) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.intValue();
    }
    private static double unit(JsonNode parent, String field, boolean positive) {
        var value = parent.get(field); var number = value == null ? Double.NaN : value.asDouble(Double.NaN);
        if (!Double.isFinite(number) || number > 1 || (positive ? number <= 0 : number < 0)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return number;
    }
    private static StudyPackRecord record(JsonNode definition, String checksum, String reviewedAt, Path path) {
        return new StudyPackRecord(text(definition, "packKey", 120), positiveInteger(definition, "version"),
                text(definition, "title", 240), checksum, reviewedAt, path);
    }
    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target);
        }
    }

    public record Preview(String checksum, String canonicalCore) {}
}
