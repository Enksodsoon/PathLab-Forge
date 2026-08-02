package org.pathlab.forge.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AnkiPackageImporterTest {
    @TempDir
    Path temp;

    @Test
    void readsRealAnkiCollectionCardsInsteadOfPretendingDelimitedTextIsApkg() throws Exception {
        var collection = temp.resolve("collection.anki2");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + collection);
                var statement = connection.createStatement()) {
            statement.execute("create table col (models text not null)");
            statement.execute("insert into col values ('{\"10\":{\"flds\":[{\"name\":\"Front\"},{\"name\":\"Back\"}],\"tmpls\":[{\"qfmt\":\"{{Front}}\",\"afmt\":\"{{FrontSide}}<hr>{{Back}}\"}]}}')");
            statement.execute("create table notes (id integer primary key, mid integer not null, flds text not null)");
            statement.execute("insert into notes values (1, 10, 'Where is the portal tract?\u001fUpper left')");
        }
        var packagePath = temp.resolve("teaching.apkg");
        try (var output = new ZipOutputStream(Files.newOutputStream(packagePath))) {
            output.putNextEntry(new ZipEntry("collection.anki2"));
            Files.copy(collection, output);
            output.closeEntry();
        }

        var imported = new AnkiPackageImporter().read(packagePath);

        assertEquals(1, imported.size());
        assertEquals("Where is the portal tract?", imported.get(0).prompt());
        assertEquals("Upper left", imported.get(0).answerKey());
    }

    @Test
    void rejectsUnsupportedTemplatesUnlessFacultyApprovesExplicitFieldMapping() throws Exception {
        var packagePath = packageWithModel(
                "{\"20\":{\"flds\":[{\"name\":\"Stem\"},{\"name\":\"Distractor\"},{\"name\":\"VerifiedKey\"}],\"tmpls\":[{\"qfmt\":\"{{Stem}}\",\"afmt\":\"{{Distractor}}\"}]}}",
                "Question\u001fWrong\u001fApproved");

        assertThrows(IllegalArgumentException.class, () -> new AnkiPackageImporter().read(packagePath));
        var cards = new AnkiPackageImporter().read(
                packagePath, new AnkiPackageImporter.FieldMapping(0, 2, true));
        assertEquals("Approved", cards.get(0).answerKey());
        assertEquals("faculty-approved", cards.get(0).keyOrigin());
    }

    private Path packageWithModel(String models, String fields) throws Exception {
        var collection = temp.resolve("mapped-collection.anki2");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + collection);
                var statement = connection.createStatement()) {
            statement.execute("create table col (models text not null)");
            statement.execute("insert into col values ('" + models.replace("'", "''") + "')");
            statement.execute("create table notes (id integer primary key, mid integer not null, flds text not null)");
            statement.execute("insert into notes values (1, 20, '" + fields.replace("'", "''") + "')");
        }
        var packagePath = temp.resolve("mapped.apkg");
        try (var output = new ZipOutputStream(Files.newOutputStream(packagePath))) {
            output.putNextEntry(new ZipEntry("collection.anki2")); Files.copy(collection, output); output.closeEntry();
        }
        return packagePath;
    }
}
