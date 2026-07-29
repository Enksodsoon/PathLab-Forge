package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatasetInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesOmeTiffSignatureWithoutLoadingWholeSlide() throws Exception {
        var source = temporaryDirectory.resolve("case.ome.tiff");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});

        var dataset = new DatasetInspector().inspect(source);

        assertEquals(DatasetFormat.OME_TIFF, dataset.format());
        assertEquals(DatasetStatus.READY, dataset.status());
        assertEquals(Files.size(source), dataset.sourceBytes());
        assertTrue(dataset.detail().contains("signature verified"));
    }

    @Test
    void rejectsAnOmeTiffExtensionWithWrongSignature() throws Exception {
        var source = temporaryDirectory.resolve("case.ome.tif");
        Files.writeString(source, "not a TIFF");

        var error = assertThrows(DatasetInspectionException.class, () ->
                new DatasetInspector().inspect(source));

        assertEquals("INVALID_TIFF_SIGNATURE", error.code());
    }

    @Test
    void reportsMissingAndPresentVsiCompanionsTruthfully() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});

        var missing = new DatasetInspector().inspect(source);
        assertEquals(DatasetStatus.NEEDS_COMPANIONS, missing.status());

        var companionDirectory = Files.createDirectories(temporaryDirectory.resolve("case"));
        Files.write(companionDirectory.resolve("frame.ets"), new byte[] {4, 5, 6});
        var complete = new DatasetInspector().inspect(source);
        assertEquals(DatasetStatus.READER_REQUIRED, complete.status());
        assertTrue(complete.detail().contains("Bio-Formats"));
    }
}
