package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Packaging check for the exact reader directory the desktop launcher will use. */
public final class PackagedReaderRuntimeVerifier {
    private PackagedReaderRuntimeVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Pass service root and INTERNAL or PRODUCTION");
        verify(Path.of(args[0]), ReaderRuntimeManifest.Channel.valueOf(args[1]));
    }

    public static void verify(Path service, ReaderRuntimeManifest.Channel channel) throws IOException {
        var runtime = service.resolve("reader-data/runtime");
        var pointer = runtime.resolve("readers-current.txt");
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Packaged reader pointer is missing");
        var fingerprint = Files.readString(pointer).trim();
        if (!fingerprint.matches("[a-f0-9]{64}")) throw new IOException("Packaged reader pointer is invalid");
        var root = runtime.resolve("readers").resolve(fingerprint);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Packaged reader root is missing");
        var manifest = ReaderRuntimeManifest.read(root.resolve("reader-runtime-manifest.json"));
        if (!manifest.fingerprint().equals(fingerprint) || !manifest.channel().equals(channel.name())
                || !manifest.platform().equals(ReaderRuntimeManifest.currentPlatform()))
            throw new IOException("Packaged reader identity mismatch");
        manifest.verify(root, channel == ReaderRuntimeManifest.Channel.PRODUCTION);
        for (var required : new String[] {"bioformats", "libvips"}) {
            if (manifest.components().stream().noneMatch(component -> component.id().equals(required)
                    && component.required() && component.included())) throw new IOException("Packaged reader is incomplete");
        }
        if (!Files.isRegularFile(root.resolve("bftools/bioformats_package.jar"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(root.resolve("vips/bin/" + (manifest.platform().startsWith("windows-") ? "vips.exe" : "vips")), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Packaged reader entry point is missing");
        if (!ReaderRuntimeManifest.read(service.resolve("reader-runtime-manifest.json")).equals(manifest))
            throw new IOException("Packaged reader manifest copy differs");
    }
}
