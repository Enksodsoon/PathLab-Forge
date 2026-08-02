package org.pathlab.forge.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            statement.execute("create table notes (id integer primary key, flds text not null)");
            statement.execute("insert into notes values (1, 'Where is the portal tract?\u001fUpper left')");
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
}
