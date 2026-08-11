package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void reloadsAndInspectsLegacySvsDatasetsWithoutBreakingNewImports() throws Exception {
        var source = temporaryDirectory.resolve("legacy.svs");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});

        var dataset = new DatasetInspector().inspect(source);

        assertEquals(DatasetFormat.SVS, dataset.format());
        assertEquals(DatasetStatus.READY, dataset.status());
        assertTrue(dataset.detail().contains("SVS"));
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

        var companionDirectory = Files.createDirectories(temporaryDirectory.resolve("_case_"));
        Files.write(companionDirectory.resolve("frame.ets"), new byte[] {4, 5, 6});
        var complete = new DatasetInspector().inspect(source);
        assertEquals(DatasetStatus.READER_REQUIRED, complete.status());
        assertTrue(complete.detail().contains("Bio-Formats"));
        assertEquals(6, complete.sourceBytes());
        assertEquals(64, complete.sourceFingerprint().length());
        assertTrue(complete.sourceInventory().contains("_case_/frame.ets"));
    }

    @Test
    void rejectsUnrelatedEtsFilesNearTheSelectedVsi() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        var unrelated = Files.createDirectories(temporaryDirectory.resolve("_different-slide_"));
        Files.write(unrelated.resolve("frame.ets"), new byte[] {4, 5, 6});

        var inspected = new DatasetInspector().inspect(source);

        assertEquals(DatasetStatus.NEEDS_COMPANIONS, inspected.status());
        assertTrue(inspected.sourceFingerprint().isEmpty());
        assertTrue(inspected.sourceInventory().isEmpty());
    }

    @Test
    void sourceFingerprintChangesWhenACompanionChanges() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        var companionDirectory = Files.createDirectories(temporaryDirectory.resolve("_case_"));
        var companion = companionDirectory.resolve("frame.ets");
        Files.write(companion, new byte[] {4, 5, 6});

        var first = new DatasetInspector().inspect(source);
        Files.write(companion, new byte[] {4, 5, 7});
        var second = new DatasetInspector().inspect(source);

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void reusesTheDatasetIdentityForTheSameSourcePath() throws Exception {
        var source = temporaryDirectory.resolve("repeatable.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});

        var first = new DatasetInspector().inspect(source);
        var second = new DatasetInspector().inspect(source);

        assertEquals(first.id(), second.id());
    }
}
