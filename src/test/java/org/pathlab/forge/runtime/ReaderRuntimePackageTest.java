package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReaderRuntimePackageTest {
    @TempDir Path temporary;

    @Test
    void createsDeterministicInternalManifestAndDetectsTampering() throws Exception {
        var source = runtimeSource("one");

        var first = ReaderRuntimeManifest.create(
                source, ReaderRuntimeManifest.Channel.INTERNAL, "windows-x86_64");
        var second = ReaderRuntimeManifest.create(
                source, ReaderRuntimeManifest.Channel.INTERNAL, "windows-x86_64");

        assertEquals(first.toJson(), second.toJson());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals("NON_REDISTRIBUTABLE", first.distributionLabel());
        assertTrue(first.components().stream().anyMatch(component -> component.id().equals("bioformats")));
        first.verify(source, false);

        Files.writeString(source.resolve("bftools/bioformats_package.jar"), "changed");
        assertThrows(IOException.class, () -> first.verify(source, false));
    }

    @Test
    void rejectsProductionManifestWhileAnyComponentReviewIsPending() throws Exception {
        var source = runtimeSource("pending");

        var error = assertThrows(IOException.class, () -> ReaderRuntimeManifest.create(
                source, ReaderRuntimeManifest.Channel.PRODUCTION, "windows-x86_64"));

        assertTrue(error.getMessage().contains("redistribution"));
    }

    @Test
    void rejectsFilesOutsideTheAllowlistedRuntimeLayout() throws Exception {
        var source = runtimeSource("unexpected");
        Files.writeString(source.resolve("unexpected-plugin.dll"), "plugin");

        assertThrows(IOException.class, () -> ReaderRuntimeManifest.create(
                source, ReaderRuntimeManifest.Channel.INTERNAL, "windows-x86_64"));
    }

    @Test
    void installsAtomicallyRollsBackAndRemovesVersionedRuntime() throws Exception {
        var dataRoot = temporary.resolve("data");
        var installer = new ReaderRuntimeInstaller(dataRoot);
        var firstSource = runtimeSource("first");
        var first = ReaderRuntimeManifest.create(
                firstSource, ReaderRuntimeManifest.Channel.INTERNAL, "windows-x86_64");

        var firstTarget = installer.install(firstSource, first);
        assertEquals(first.fingerprint(), Files.readString(
                dataRoot.resolve("runtime/readers-current.txt")).trim());
        assertTrue(Files.isRegularFile(firstTarget.resolve("NON_REDISTRIBUTABLE")));
        assertEquals(firstTarget.resolve("bftools"),
                ReaderRuntimeLocator.componentRoot(dataRoot, "bftools").orElseThrow());

        var secondSource = runtimeSource("second");
        Files.writeString(secondSource.resolve("bftools/bioformats_package.jar"), "second-version");
        var second = ReaderRuntimeManifest.create(
                secondSource, ReaderRuntimeManifest.Channel.INTERNAL, "windows-x86_64");
        installer.install(secondSource, second);
        assertEquals(second.fingerprint(), installer.activeFingerprint().orElseThrow());

        installer.rollback();
        assertEquals(first.fingerprint(), installer.activeFingerprint().orElseThrow());

        installer.uninstall(first.fingerprint());
        assertFalse(installer.activeFingerprint().isPresent());
        assertFalse(Files.exists(firstTarget));
    }

    @Test
    void rejectsManifestForAnotherArchitecture() throws Exception {
        var source = runtimeSource("architecture");
        var manifest = ReaderRuntimeManifest.create(
                source, ReaderRuntimeManifest.Channel.INTERNAL, "macos-arm64");

        assertThrows(IOException.class,
                () -> new ReaderRuntimeInstaller(temporary.resolve("data-architecture"))
                        .install(source, manifest));
    }

    @Test
    void locatesRuntimeBesidePackagedApplicationWithFreshUserData() throws Exception {
        var appRoot = temporary.resolve("application");
        Files.createDirectories(appRoot);
        var executable = Files.writeString(appRoot.resolve("PathLab Forge.exe"), "launcher");
        var source = runtimeSource("packaged-source");
        var manifest = ReaderRuntimeManifest.create(
                source, ReaderRuntimeManifest.Channel.INTERNAL, "windows-x86_64");
        new ReaderRuntimeInstaller(appRoot.resolve("reader-data")).install(source, manifest);
        var previous = System.getProperty("jpackage.app-path");
        try {
            System.setProperty("jpackage.app-path", executable.toString());
            assertEquals(appRoot.resolve("reader-data/runtime/readers").resolve(manifest.fingerprint()),
                    ReaderRuntimeLocator.activeRoot(temporary.resolve("fresh-user-data")).orElseThrow());
        } finally {
            if (previous == null) System.clearProperty("jpackage.app-path");
            else System.setProperty("jpackage.app-path", previous);
        }
    }

    private Path runtimeSource(String name) throws IOException {
        var source = temporary.resolve(name);
        Files.createDirectories(source.resolve("bftools"));
        Files.createDirectories(source.resolve("vips/bin"));
        Files.createDirectories(source.resolve("licenses/bioformats"));
        Files.createDirectories(source.resolve("licenses/libvips"));
        Files.writeString(source.resolve("bftools/bioformats_package.jar"), "bioformats-8.5");
        Files.writeString(source.resolve("vips/bin/vips.exe"), "libvips-8.18.2");
        Files.writeString(source.resolve("licenses/bioformats/LICENSE.txt"), "review pending");
        Files.writeString(source.resolve("licenses/libvips/LICENSE.txt"), "review pending");
        return source;
    }
}
