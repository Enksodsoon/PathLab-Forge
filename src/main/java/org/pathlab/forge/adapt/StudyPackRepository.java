package org.pathlab.forge.adapt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class StudyPackRepository {
    private static final int MAX_PACK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TASKS = 5_000;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema", "packKey", "version", "title", "courseId", "objectives", "slides", "tasks");
    private static final Set<String> SLIDE_FIELDS = Set.of(
            "schema", "viewerSlideId", "sha256", "displayName", "license");
    private static final Set<String> COMMON_TASK_FIELDS = Set.of(
            "schema", "type", "id", "slideId", "prompt", "source", "author", "license", "revision");
    private static final Set<String> KEYED_TASK_FIELDS = union(
            COMMON_TASK_FIELDS, Set.of("answerKey", "keyApproval", "choices"));
    private static final Set<String> SPATIAL_TASK_FIELDS = union(
            COMMON_TASK_FIELDS,
            Set.of("targetX", "targetY", "targetWidth", "targetHeight", "tolerance"));
    private final Path root;

    public StudyPackRepository(Path managedRoot) {
        root = managedRoot.toAbsolutePath().normalize()
                .resolve("research").resolve("adapt-v1").resolve("packs");
    }

    public StudyPackRecord save(String body) throws IOException {
        var validated = validate(body);
        Files.createDirectories(root);
        claimVersion(validated);
        var target = target(validated.checksum());
        if (!Files.isRegularFile(target)) {
            var partial = target.resolveSibling(target.getFileName() + ".partial-" + UUID.randomUUID());
            try {
                Files.writeString(partial, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                moveWithoutReplace(partial, target);
            } catch (FileAlreadyExistsException race) {
                if (!Files.isRegularFile(target)) {
                    throw race;
                }
            } finally {
                Files.deleteIfExists(partial);
            }
        }
        return record(validated, target);
    }

    public String read(String checksum) throws IOException {
        var target = target(checksum);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Study Pack was not found");
        }
        var body = Files.readString(target, StandardCharsets.UTF_8);
        if (!StudyPackCanonicalJson.checksum(body).equals(checksum)) {
            throw new IOException("Study Pack checksum no longer matches");
        }
        validate(body);
        return body;
    }

    public List<StudyPackSlide> viewerSlides(String body) {
        return validate(body).slides();
    }

    public List<StudyPackRecord> list() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}\\.json"))
                    .map(path -> {
                        try {
                            var body = Files.readString(path, StandardCharsets.UTF_8);
                            var validated = validate(body);
                            if (!path.getFileName().toString().equals(validated.checksum() + ".json")) {
                                throw new IOException("Study Pack file checksum is invalid");
                            }
                            return record(validated, path);
                        } catch (IOException error) {
                            throw new StudyPackReadException(error);
                        }
                    })
                    .sorted(Comparator.comparing(StudyPackRecord::packKey)
                            .thenComparing(StudyPackRecord::version).reversed())
                    .toList();
        } catch (StudyPackReadException error) {
            throw (IOException) error.getCause();
        }
    }

    private void claimVersion(ValidatedPack pack) throws IOException {
        var claim = root.resolve(sha256(pack.packKey()).substring(0, 32)
                + "-v" + pack.version() + ".claim");
        try {
            Files.writeString(claim, pack.checksum(), StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException exists) {
            if (!Files.readString(claim, StandardCharsets.US_ASCII).equals(pack.checksum())) {
                throw new IllegalArgumentException(
                        "Study Pack versions are immutable; create a new version");
            }
        }
    }

    private static ValidatedPack validate(String body) {
        if (body == null || body.isBlank()
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_PACK_BYTES) {
            throw new IllegalArgumentException("Study Pack is empty or too large");
        }
        final JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException error) {
            throw new IllegalArgumentException("Study Pack JSON is invalid", error);
        }
        requireObject(root, "Study Pack");
        rejectUnknown(root, ROOT_FIELDS, "Study Pack");
        if (!"pathlab.study-pack/1".equals(text(root, "schema"))) {
            throw new IllegalArgumentException("Unsupported Study Pack schema");
        }
        var packKey = text(root, "packKey", 120);
        if (!packKey.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("Study Pack packKey is invalid");
        }
        var title = text(root, "title", 240);
        text(root, "courseId", 160);
        var version = positiveInteger(root, "version");
        stringArray(root, "objectives", 40, true);
        var slides = requiredArray(root, "slides", 100);
        var slideIds = new HashSet<String>();
        var slideReferences = new ArrayList<StudyPackSlide>();
        for (var slide : slides) {
            requireObject(slide, "slide");
            rejectUnknown(slide, SLIDE_FIELDS, "slide");
            optionalSchema(slide, "pathlab.slide-reference/1", "slide");
            var id = text(slide, "viewerSlideId", 100);
            if (!slideIds.add(id)) throw new IllegalArgumentException("Viewer slide IDs must be unique");
            var checksum = text(slide, "sha256", 64);
            if (!checksum.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Slide checksum is invalid");
            var displayName = text(slide, "displayName", 200);
            var license = text(slide, "license", 240);
            slideReferences.add(new StudyPackSlide(id, checksum, displayName, license));
        }
        var tasks = requiredArray(root, "tasks", MAX_TASKS);
        if (tasks.isEmpty()) throw new IllegalArgumentException("Study Pack needs at least one task");
        var taskIds = new HashSet<String>();
        var masteryEligible = false;
        for (var task : tasks) {
            requireObject(task, "task");
            var type = text(task, "type");
            var allowed = switch (type) {
                case "keyed" -> KEYED_TASK_FIELDS;
                case "spatial" -> SPATIAL_TASK_FIELDS;
                default -> throw new IllegalArgumentException("Unsupported Study Pack task type");
            };
            rejectUnknown(task, allowed, type + " task");
            optionalSchema(task, "pathlab.study-task/1", "task");
            var taskId = text(task, "id", 120);
            if (!taskId.matches("[A-Za-z0-9._-]{1,120}")) {
                throw new IllegalArgumentException("Study Pack task id is invalid");
            }
            if (!taskIds.add(taskId)) throw new IllegalArgumentException("Task IDs must be unique");
            if (!slideIds.contains(text(task, "slideId", 100))) throw new IllegalArgumentException("Task slide is not declared");
            text(task, "prompt", 2000); text(task, "source", 500);
            text(task, "author", 240); text(task, "license", 240); text(task, "revision", 120);
            if ("keyed".equals(type)) {
                text(task, "answerKey", 2000);
                var approval = text(task, "keyApproval", 16);
                if (!Set.of("imported", "faculty-approved").contains(approval)) {
                    throw new IllegalArgumentException("Keyed task approval is invalid");
                }
                optionalStringArray(task, "choices", 20);
                masteryEligible = true;
            } else {
                var x = unit(task, "targetX"); var y = unit(task, "targetY");
                var width = positiveUnit(task, "targetWidth"); var height = positiveUnit(task, "targetHeight");
                var tolerance = positiveUnit(task, "tolerance");
                if (tolerance > 0.5) throw new IllegalArgumentException("Spatial tolerance is too large");
                if (x + width > 1 || y + height > 1) {
                    throw new IllegalArgumentException("Spatial target escapes normalized slide bounds");
                }
            }
        }
        return new ValidatedPack(packKey, version, title,
                StudyPackCanonicalJson.checksum(body), masteryEligible,
                List.copyOf(slideReferences));
    }

    private static JsonNode requiredArray(JsonNode node, String name, int maximum) {
        var value = node.get(name);
        if (value == null || !value.isArray() || value.size() > maximum) {
            throw new IllegalArgumentException("Study Pack " + name + " is invalid or too large");
        }
        return value;
    }

    private static void stringArray(JsonNode node, String name, int maximum, boolean requireNonEmpty) {
        var values = requiredArray(node, name, maximum);
        if (requireNonEmpty && values.isEmpty()) {
            throw new IllegalArgumentException("Study Pack " + name + " must not be empty");
        }
        for (var value : values) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("Study Pack " + name + " must contain text");
            }
        }
    }

    private static String text(JsonNode node, String name) {
        return text(node, name, 4096);
    }

    private static String text(JsonNode node, String name, int maximum) {
        var value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw new IllegalArgumentException("Study Pack omitted or invalid " + name);
        }
        return value.textValue();
    }

    private static void optionalSchema(JsonNode node, String expected, String label) {
        var value = node.get("schema");
        if (value != null && (!value.isTextual() || !expected.equals(value.textValue()))) {
            throw new IllegalArgumentException(label + " schema is invalid");
        }
    }

    private static void optionalStringArray(JsonNode node, String name, int maximum) {
        var value = node.get(name);
        if (value == null) return;
        if (!value.isArray() || value.size() > maximum) {
            throw new IllegalArgumentException("Study Pack " + name + " is invalid or too large");
        }
        for (var item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("Study Pack " + name + " must contain text");
            }
        }
    }

    private static int positiveInteger(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new IllegalArgumentException("Study Pack " + name + " is invalid");
        }
        return value.intValue();
    }

    private static double unit(JsonNode node, String name) {
        var value = number(node, name);
        if (value < 0 || value > 1) throw new IllegalArgumentException(name + " must be normalized");
        return value;
    }

    private static double positiveUnit(JsonNode node, String name) {
        var value = number(node, name);
        if (value <= 0 || value > 1) throw new IllegalArgumentException(name + " must be a positive normalized value");
        return value;
    }

    private static double number(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("Study Pack " + name + " is invalid");
        }
        return value.doubleValue();
    }

    private static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(label + " must be an object");
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String label) {
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException(label + " contains unknown field " + name);
        });
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        var result = new HashSet<>(first); result.addAll(second); return Set.copyOf(result);
    }

    private Path target(String checksum) {
        if (checksum == null || !checksum.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Study Pack checksum is invalid");
        var target = root.resolve(checksum + ".json").toAbsolutePath().normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Study Pack path escapes managed storage");
        return target;
    }

    private static void moveWithoutReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
    }

    private static StudyPackRecord record(ValidatedPack pack, Path path) {
        return new StudyPackRecord(pack.packKey(), pack.version(), pack.title(), pack.checksum(), pack.masteryEligible(), path);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record StudyPackSlide(String viewerSlideId, String sha256, String displayName, String license) {}
    private record ValidatedPack(String packKey, int version, String title, String checksum,
            boolean masteryEligible, List<StudyPackSlide> slides) {}
    private static final class StudyPackReadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StudyPackReadException(IOException cause) { super(cause); }
    }
}
