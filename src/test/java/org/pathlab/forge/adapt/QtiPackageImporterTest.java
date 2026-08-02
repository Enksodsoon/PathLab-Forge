package org.pathlab.forge.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QtiPackageImporterTest {
    @TempDir Path temp;

    @Test
    void importsBoundedPackagedQtiWithPerItemProvenance() throws Exception {
        var archive = temp.resolve("bank.qti");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("item.xml"));
            output.write(("""
                    <assessmentItem identifier="q1"><metadata><source>Faculty bank</source><author>Dr R</author><license>CC BY</license><revision>7</revision><value>Not the answer</value></metadata>
                    <responseDeclaration><correctResponse><value>A</value></correctResponse></responseDeclaration><itemBody>Portal tract?</itemBody></assessmentItem>
                    """).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var item = new QtiPackageImporter().read(archive).get(0);
        assertEquals("A", item.answerKey());
        assertEquals("Faculty bank", item.source());
        assertEquals("Dr R", item.author());
        assertEquals("CC BY", item.license());
        assertEquals("7", item.revision());
    }

    @Test
    void rejectsArchiveTraversal() throws Exception {
        var archive = temp.resolve("bad.qti");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("../item.xml")); output.write("<x/>".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () -> new QtiPackageImporter().read(archive));
    }
}
