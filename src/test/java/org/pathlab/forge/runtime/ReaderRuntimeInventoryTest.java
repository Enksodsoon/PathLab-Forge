package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReaderRuntimeInventoryTest {
    @TempDir Path temporary;

    @Test
    void reportsPackagedPendingAndBuiltInComponentsWithoutExposingPaths() throws Exception {
        var source = temporary.resolve("source");
        Files.createDirectories(source.resolve("bftools"));
        Files.createDirectories(source.resolve("vips/bin"));
        Files.writeString(source.resolve("bftools/bioformats_package.jar"), "bioformats");
        Files.writeString(source.resolve("vips/bin/vips.exe"), "vips");
        var manifest = ReaderRuntimeManifest.create(
                source, ReaderRuntimeManifest.Channel.INTERNAL, "windows-x86_64");
        new ReaderRuntimeInstaller(temporary.resolve("data")).install(source, manifest);

        var inventory = ReaderRuntimeInventory.inspect(temporary.resolve("data"));
        var bioFormats = inventory.stream().filter(item -> item.id().equals("bioformats"))
                .findFirst().orElseThrow();

        assertTrue(bioFormats.available());
        assertEquals("PACKAGED", bioFormats.source());
        assertEquals("LICENSE_PENDING", bioFormats.diagnosticCode());
        assertEquals(manifest.fingerprint(), bioFormats.fingerprint());
        assertTrue(inventory.stream().anyMatch(item -> item.id().equals("motic-mds")
                && item.available() && item.source().equals("BUILT_IN")));
        assertTrue(inventory.stream().noneMatch(item -> item.detail().contains(temporary.toString())));
    }
}
