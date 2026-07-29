package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LocalDatasetArtifactIdentityTest {
    @Test
    void changingExportConfigurationInvalidatesStaleArtifactAndApproval() {
        var configured = dataset().withExportConfiguration(
                DatasetStatus.READY_TO_CONVERT,
                "configured",
                3,
                72_792,
                66_004,
                1.0,
                1,
                0,
                0,
                72_792,
                66_004);
        var converted = configured
                .withArtifactRevision(
                        DatasetStatus.PACKAGE_READY,
                        "ready",
                        "artifact/export.ome.tif",
                        "abc",
                        "revision-1")
                .withApprovedArtifact("revision-1");

        var changed = converted.withExportConfiguration(
                DatasetStatus.READY_TO_CONVERT,
                "new series",
                2,
                512,
                184,
                1.0,
                1,
                0,
                0,
                512,
                184);

        assertTrue(changed.outputPath().isEmpty());
        assertTrue(changed.sha256().isEmpty());
        assertTrue(changed.currentArtifactRevision().isEmpty());
        assertTrue(changed.approvedArtifactRevision().isEmpty());
        assertFalse(changed.configurationRevision().equals(
                converted.configurationRevision()));
    }

    @Test
    void selectingTheSameConfigurationRetainsItsCurrentArtifact() {
        var configured = dataset().withExportConfiguration(
                DatasetStatus.READY_TO_CONVERT,
                "configured",
                3,
                72_792,
                66_004,
                1.5,
                1,
                10,
                20,
                300,
                400);
        var converted = configured.withArtifactRevision(
                DatasetStatus.PACKAGE_READY,
                "ready",
                "artifact/export.ome.tif",
                "abc",
                "revision-1");

        var unchanged = converted.withExportConfiguration(
                DatasetStatus.READY_TO_CONVERT,
                "same",
                3,
                72_792,
                66_004,
                1.5,
                1,
                10,
                20,
                300,
                400);

        assertEquals("revision-1", unchanged.currentArtifactRevision());
        assertEquals("artifact/export.ome.tif", unchanged.outputPath());
        assertThrows(
                IllegalArgumentException.class,
                () -> unchanged.withApprovedArtifact("revision-2"));
    }

    private static LocalDataset dataset() {
        return new LocalDataset(
                "dataset-1",
                "case.vsi",
                "C:\\slides\\case.vsi",
                100,
                DatasetFormat.VSI,
                DatasetStatus.READER_REQUIRED,
                "ready",
                "",
                "",
                -1,
                0,
                0,
                1.0,
                0,
                0,
                0,
                0,
                0,
                "a".repeat(64),
                "case.vsi|100|0|" + "b".repeat(64),
                "",
                "",
                "");
    }
}
