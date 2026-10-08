package org.pathlab.forge.server;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedExportServiceTest {
    @TempDir Path root;
    @Test void corruptArtifactPreservesExistingDestinationAndVerifiedCopyReplacesIt() throws Exception {
        var source = root.resolve("source.ome.tif");
        var target = root.resolve("export.ome.tif");
        Files.writeString(source, "verified pixels"); Files.writeString(target, "previous completed export");
        try (var service = new VerifiedExportService()) {
            service.submit(source, "0".repeat(64), target);
            await(service);
            assertEquals("FAILED", service.state().status());
            assertEquals("previous completed export", Files.readString(target));
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            service.submit(source, hash, target); await(service);
            assertEquals("COMPLETE", service.state().status());
            assertEquals("verified pixels", Files.readString(target));
            assertThrows(java.io.IOException.class, () -> service.submit(source, hash, source));
        }
    }
    private void await(VerifiedExportService service) throws Exception {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (service.active() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(service.active());
    }
}
