package org.pathlab.forge.adapt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Reads the SQLite collection from a real Anki .apkg archive with fixed size limits. */
public final class AnkiPackageImporter {
    private static final long MAX_PACKAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_COLLECTION_BYTES = 32L * 1024 * 1024;
    private static final int MAX_CARDS = 10_000;

    public List<ImportedCard> read(Path packagePath) throws IOException {
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
            return readCollection(collection);
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

    private static List<ImportedCard> readCollection(Path collection) throws IOException {
        var cards = new ArrayList<ImportedCard>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + collection);
                var statement = connection.prepareStatement(
                        "select id, flds from notes order by id limit ?")) {
            statement.setInt(1, MAX_CARDS + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (cards.size() == MAX_CARDS) {
                        throw new IllegalArgumentException("Anki package exceeds 10000 notes");
                    }
                    var fields = rows.getString("flds").split("\\u001f", -1);
                    if (fields.length < 2 || plain(fields[0]).isBlank() || plain(fields[1]).isBlank()) {
                        throw new IllegalArgumentException("Anki note is missing a front or imported answer");
                    }
                    cards.add(new ImportedCard(
                            "anki-" + rows.getLong("id"), plain(fields[0]), plain(fields[1])));
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

    private static String plain(String value) {
        return value.replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ").trim();
    }

    public record ImportedCard(String id, String prompt, String answerKey) {}
}
