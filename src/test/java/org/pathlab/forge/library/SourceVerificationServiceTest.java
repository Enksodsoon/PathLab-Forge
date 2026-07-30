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
}
