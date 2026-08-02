package org.pathlab.forge.adapt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public final class StudyPackRepository {
    private static final int MAX_PACK_CHARACTERS = 4 * 1024 * 1024;
    private static final Pattern KEYED_TASK = Pattern.compile(
            "\\{[^{}]*\\\"type\\\"\\s*:\\s*\\\"keyed\\\"[^{}]*}",
            Pattern.DOTALL);
    private final Path root;

    public StudyPackRepository(Path managedRoot) {
        root = managedRoot.toAbsolutePath().normalize()
                .resolve("research").resolve("adapt-v1").resolve("packs");
    }

    public StudyPackRecord save(String body) throws IOException {
        var validated = validate(body);
        Files.createDirectories(root);
        for (var existing : list()) {
            if (existing.packKey().equals(validated.packKey())
                    && existing.version() == validated.version()
                    && !existing.checksum().equals(validated.checksum())) {
                throw new IllegalArgumentException(
                        "Study Pack versions are immutable; create a new version");
            }
        }
        var target = target(validated.checksum());
        if (!Files.isRegularFile(target)) {
            var partial = target.resolveSibling(target.getFileName() + ".partial");
            Files.writeString(partial, body, StandardCharsets.UTF_8);
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, target);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
        return new StudyPackRecord(
                validated.packKey(),
                validated.version(),
                validated.title(),
                validated.checksum(),
                validated.masteryEligible(),
                target);
    }

    public String read(String checksum) throws IOException {
        var target = target(checksum);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("Study Pack was not found");
        }
        var body = Files.readString(target, StandardCharsets.UTF_8);
        if (!sha256(body).equals(checksum)) {
            throw new IOException("Study Pack checksum no longer matches");
        }
        return body;
    }

    public List<StudyPackRecord> list() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}\\.json"))
                    .map(path -> {
                        try {
                            var body = Files.readString(path, StandardCharsets.UTF_8);
                            var validated = validate(body);
                            if (!path.getFileName().toString()
                                    .equals(validated.checksum() + ".json")) {
                                throw new IOException("Study Pack file checksum is invalid");
                            }
                            return new StudyPackRecord(
                                    validated.packKey(),
                                    validated.version(),
                                    validated.title(),
                                    validated.checksum(),
                                    validated.masteryEligible(),
                                    path);
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

    private ValidatedPack validate(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_PACK_CHARACTERS) {
            throw new IllegalArgumentException("Study Pack is empty or too large");
        }
        if (!"pathlab.study-pack/1".equals(string(body, "schema"))) {
            throw new IllegalArgumentException("Unsupported Study Pack schema");
        }
        if (!body.contains("\"slides\"") || !body.contains("\"tasks\"")) {
            throw new IllegalArgumentException("Study Pack omitted slides or tasks");
        }
        var keyed = KEYED_TASK.matcher(body);
        var masteryEligible = false;
        while (keyed.find()) {
            masteryEligible = true;
            var task = keyed.group();
            for (var field : List.of("answerKey", "source", "author", "license", "revision")) {
                if (string(task, field).isBlank()) {
                    throw new IllegalArgumentException(
                            "Keyed tasks require complete answer provenance");
                }
            }
        }
        return new ValidatedPack(
                string(body, "packKey"),
                integer(body, "version"),
                string(body, "title"),
                sha256(body),
                masteryEligible);
    }

    private Path target(String checksum) {
        if (checksum == null || !checksum.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Study Pack checksum is invalid");
        }
        var target = root.resolve(checksum + ".json").toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Study Pack path escapes managed storage");
        }
        return target;
    }

    private static String string(String json, String key) {
        var matcher = Pattern.compile(
                        "\\\"" + Pattern.quote(key)
                                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Study Pack omitted " + key);
        }
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\").trim();
    }

    private static int integer(String json, String key) {
        var matcher = Pattern.compile(
                        "\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*([0-9]+)")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Study Pack omitted " + key);
        }
        var value = Integer.parseInt(matcher.group(1));
        if (value < 1) {
            throw new IllegalArgumentException("Study Pack version is invalid");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record ValidatedPack(
            String packKey, int version, String title, String checksum, boolean masteryEligible) {}

    private static final class StudyPackReadException extends RuntimeException {
        StudyPackReadException(IOException cause) {
            super(cause);
        }
    }
}
