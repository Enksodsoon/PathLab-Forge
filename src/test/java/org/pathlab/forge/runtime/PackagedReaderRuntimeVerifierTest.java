package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackagedReaderRuntimeVerifierTest {
    @TempDir Path root;

    @Test void verifiesAssembledPostSigningBytesAndRejectsMissingOrStaleActivation() throws Exception {
        var source = root.resolve("source");
        var service = root.resolve("service");
        Files.createDirectories(source.resolve("bftools"));
        Files.createDirectories(source.resolve("vips/bin"));
        Files.createDirectories(service);
        Files.writeString(source.resolve("bftools/bioformats_package.jar"), "synthetic test reader");
        var vips = source.resolve("vips/bin/" + (ReaderRuntimeManifest.currentPlatform().startsWith("windows-") ? "vips.exe" : "vips"));
        Files.writeString(vips, "synthetic test native bytes");
        var before = ReaderRuntimeManifest.create(source, ReaderRuntimeManifest.Channel.INTERNAL, ReaderRuntimeManifest.currentPlatform());
        Files.writeString(vips, "synthetic test bytes after signing");
        var after = ReaderRuntimeManifest.create(source, ReaderRuntimeManifest.Channel.INTERNAL, ReaderRuntimeManifest.currentPlatform());
        var installed = new ReaderRuntimeInstaller(service.resolve("reader-data")).install(source, after);
        Files.writeString(service.resolve("reader-runtime-manifest.json"), after.toJson());
        assertDoesNotThrow(() -> PackagedReaderRuntimeVerifier.verify(service, ReaderRuntimeManifest.Channel.INTERNAL));
        assertThrows(Exception.class, () -> PackagedReaderRuntimeVerifier.verify(service, ReaderRuntimeManifest.Channel.PRODUCTION));
        Files.writeString(installed.resolve("reader-runtime-manifest.json"), before.toJson());
        assertThrows(Exception.class, () -> PackagedReaderRuntimeVerifier.verify(service, ReaderRuntimeManifest.Channel.INTERNAL));
        Files.writeString(installed.resolve("reader-runtime-manifest.json"), after.toJson());
        Files.writeString(service.resolve("reader-data/runtime/readers-current.txt"), "0".repeat(64));
        assertThrows(Exception.class, () -> PackagedReaderRuntimeVerifier.verify(service, ReaderRuntimeManifest.Channel.INTERNAL));
        assertThrows(Exception.class, () -> PackagedReaderRuntimeVerifier.verify(root.resolve("missing"), ReaderRuntimeManifest.Channel.PRODUCTION));
    }
}
