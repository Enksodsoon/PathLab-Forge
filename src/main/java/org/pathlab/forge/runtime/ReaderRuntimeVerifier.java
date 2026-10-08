package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Same fail-closed verification entry point on every packaging host. */
public final class ReaderRuntimeVerifier {
    private ReaderRuntimeVerifier() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ReaderRuntimeVerifier <source> <lock>");
        verify(Path.of(args[0]), Path.of(args[1]));
    }
    public static void verify(Path source, Path lock) throws IOException {
        var properties = new Properties();
        try (var input = Files.newInputStream(lock)) { properties.load(input); }
        if (!"APPROVED".equals(properties.getProperty("redistribution.status")))
            throw new IOException("Reader redistribution review is incomplete; production installers are blocked");
        var platform = ReaderRuntimeManifest.currentPlatform();
        if (!platform.equals(properties.getProperty("platform")))
            throw new IOException("Runtime lock must identify this exact packaging platform");
        var manifest = ReaderRuntimeManifest.create(source, ReaderRuntimeManifest.Channel.PRODUCTION, platform);
        manifest.verify(source, true);
        if (!manifest.fingerprint().equals(properties.getProperty("runtime.fingerprint")))
            throw new IOException("Complete runtime inventory does not match the reviewed lock");
        verifyHash(source.resolve("bftools/bioformats_package.jar"), properties.getProperty("bioformats.sha256"));
        verifyHash(source.resolve(platform.startsWith("windows-") ? "vips/bin/vips.exe" : "vips/bin/vips"),
                properties.getProperty("libvips.sha256"));
    }
    private static void verifyHash(Path path, String expected) throws IOException {
        if (expected == null || !expected.matches("[0-9a-f]{64}")) throw new IOException("Runtime hash review is incomplete");
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) throw new IOException("Runtime hash mismatch: " + path.getFileName());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
