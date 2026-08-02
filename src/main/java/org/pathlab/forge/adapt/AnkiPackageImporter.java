package org.pathlab.forge.adapt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.ZipFile;

/** Reads the SQLite collection from a real Anki .apkg archive with fixed size limits. */
public final class AnkiPackageImporter {
    private static final long MAX_PACKAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_COLLECTION_BYTES = 32L * 1024 * 1024;
    private static final int MAX_CARDS = 10_000;

    public List<ImportedCard> read(Path packagePath) throws IOException {
        return read(packagePath, null);
    }

    public List<ImportedCard> read(Path packagePath, FieldMapping mapping) throws IOException {
        if (mapping != null && !mapping.facultyApproved()) {
            throw new IllegalArgumentException("Faculty approval is required for explicit Anki field mapping");
        }
        if (packagePath == null || !Files.isRegularFile(packagePath)
                || Files.size(packagePath) > MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("Anki package is missing or exceeds 32 MiB");
        }
        var collection = Files.createTempFile("pathlab-anki-", ".sqlite");
        try {
            try (var archive = new ZipFile(packagePath.toFile())) {
                var entry = archive.getEntry("collection.anki21");
                if (entry == null) {
                    entry = archive.getEntry("collection.anki2");
                }
                if (entry == null || entry.isDirectory()) {
                    throw new IllegalArgumentException("Anki package has no collection database");
                }
                try (var input = archive.getInputStream(entry)) {
                    copyBounded(input, collection);
                }
            }
            return readCollection(collection, mapping);
        } catch (java.util.zip.ZipException error) {
            throw new IllegalArgumentException("Anki package is not a valid .apkg archive", error);
        } finally {
            Files.deleteIfExists(collection);
        }
    }

    private static void copyBounded(InputStream input, Path target) throws IOException {
        var total = 0L;
        try (var output = Files.newOutputStream(target)) {
            var buffer = new byte[8192];
            for (var count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                total += count;
                if (total > MAX_COLLECTION_BYTES) {
                    throw new IllegalArgumentException("Anki collection exceeds 32 MiB");
                }
                output.write(buffer, 0, count);
            }
        }
    }

    private static List<ImportedCard> readCollection(Path collection, FieldMapping mapping) throws IOException {
        var cards = new ArrayList<ImportedCard>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + collection)) {
            var models = models(connection);
            try (
                var statement = connection.prepareStatement(
                        "select id, mid, flds from notes order by id limit ?")) {
                statement.setInt(1, MAX_CARDS + 1);
                try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (cards.size() == MAX_CARDS) {
                        throw new IllegalArgumentException("Anki package exceeds 10000 notes");
                    }
                    var fields = rows.getString("flds").split("\\u001f", -1);
                    var indexes = mapping == null ? models.get(rows.getLong("mid")) : mapping;
                    if (indexes == null) {
                        throw new IllegalArgumentException(
                                "Anki template is unsupported; faculty field mapping is required");
                    }
                    if (indexes.promptField() >= fields.length || indexes.answerField() >= fields.length
                            || plain(fields[indexes.promptField()]).isBlank()
                            || plain(fields[indexes.answerField()]).isBlank()) {
                        throw new IllegalArgumentException("Anki note is missing a front or imported answer");
                    }
                    cards.add(new ImportedCard(
                            "anki-" + rows.getLong("id"), plain(fields[indexes.promptField()]),
                            plain(fields[indexes.answerField()]), indexes.facultyApproved()
                                    ? "faculty-approved" : "imported"));
                }
            }
            }
        } catch (SQLException error) {
            throw new IllegalArgumentException("Anki collection database cannot be read", error);
        }
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("Anki package has no importable notes");
        }
        return List.copyOf(cards);
    }

    private static Map<Long, FieldMapping> models(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("select models from col limit 1")) {
            if (!row.next()) throw new IllegalArgumentException("Anki collection has no model metadata");
            try {
                var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.getString(1));
                var result = new HashMap<Long, FieldMapping>();
                root.properties().forEach(entry -> {
                    var model = entry.getValue();
                    var fields = model.path("flds"); var templates = model.path("tmpls");
                    var front = -1; var back = -1;
                    for (var index = 0; index < fields.size(); index++) {
                        if ("Front".equals(fields.get(index).path("name").asText())) front = index;
                        if ("Back".equals(fields.get(index).path("name").asText())) back = index;
                    }
                    if (front >= 0 && back >= 0 && templates.isArray() && !templates.isEmpty()
                            && templates.get(0).path("qfmt").asText().contains("{{Front}}")
                            && templates.get(0).path("afmt").asText().contains("{{Back}}")) {
                        result.put(Long.parseLong(entry.getKey()), new FieldMapping(front, back, false));
                    }
                });
                return result;
            } catch (IOException | NumberFormatException error) {
                throw new IllegalArgumentException("Anki model metadata is invalid", error);
            }
        }
    }

    private static String plain(String value) {
        return value.replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ").trim();
    }

    public record FieldMapping(int promptField, int answerField, boolean facultyApproved) {
        public FieldMapping {
            if (promptField < 0 || answerField < 0 || promptField == answerField
                    || promptField > 99 || answerField > 99) {
                throw new IllegalArgumentException("Faculty-approved Anki field mapping is invalid");
            }
        }

    }

    public record ImportedCard(String id, String prompt, String answerKey, String keyOrigin) {}
}
