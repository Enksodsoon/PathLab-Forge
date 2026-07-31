package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceVerificationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void fastImportReturnsSnapshotThenBackgroundDigestPersistsOnce() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        Files.write(
                Files.createDirectories(temporaryDirectory.resolve("_case_"))
                        .resolve("frame.ets"),
                new byte[] {4, 5, 6});
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("library.properties"));
        var pending = new DatasetInspector().inspectFast(source);
        repository.save(pending);

        assertEquals(DatasetStatus.VERIFYING_SOURCE, pending.status());
        assertEquals("", pending.sourceFingerprint());
        assertFalse(pending.sourceInventory().isBlank());

        try (var verification = new SourceVerificationService(repository)) {
            verification.verifyAsync(pending);
            var verified = verification.await(pending.id());

            assertEquals(DatasetStatus.READER_REQUIRED, verified.status());
            assertEquals(64, verified.sourceFingerprint().length());
            assertEquals(verified, repository.find(pending.id()).orElseThrow());
        }
    }

    @Test
    void verificationPromotesAnInspectedDatasetWithoutReplacingItsConfiguration() throws Exception {
        var source = temporaryDirectory.resolve("configured.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        Files.write(
                Files.createDirectories(temporaryDirectory.resolve("configured"))
                        .resolve("frame.ets"),
                new byte[] {4, 5, 6});
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("configured-library.properties"));
        var pending = new DatasetInspector().inspectFast(source);
        var configured = pending.withExportConfiguration(
                DatasetStatus.VERIFYING_SOURCE,
                "1 image series found; source verification is still running",
                2,
                1200,
                800,
                2.0,
                360_000,
                10,
                20,
                600,
                400);
        repository.save(configured);

        try (var verification = new SourceVerificationService(repository)) {
            var verified = verification.await(configured.id());

            assertEquals(DatasetStatus.READY_TO_CONVERT, verified.status());
            assertEquals(2, verified.selectedSeries());
            assertEquals(10, verified.cropX());
            assertEquals(20, verified.cropY());
            assertEquals(600, verified.cropWidth());
            assertEquals(400, verified.cropHeight());
            assertEquals(configured.configurationRevision(), verified.configurationRevision());
            assertEquals(64, verified.sourceFingerprint().length());
        }
    }
}
