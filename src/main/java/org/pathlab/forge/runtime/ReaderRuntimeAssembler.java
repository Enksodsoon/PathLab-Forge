package org.pathlab.forge.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Build entry point; source runtimes stay external and output stays under build/. */
public final class ReaderRuntimeAssembler {
    private ReaderRuntimeAssembler() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: ReaderRuntimeAssembler <source> <package-data-root> <INTERNAL|PRODUCTION> <platform>");
        }
        var source = Path.of(args[0]).toAbsolutePath().normalize();
        var dataRoot = Path.of(args[1]).toAbsolutePath().normalize();
        var channel = ReaderRuntimeManifest.Channel.valueOf(args[2]);
        var manifest = ReaderRuntimeManifest.create(source, channel, args[3]);
        var installed = new ReaderRuntimeInstaller(dataRoot).install(source, manifest);
        var packageRoot = dataRoot.getParent();
        Files.writeString(packageRoot.resolve("reader-runtime-manifest.json"), manifest.toJson(),
                StandardCharsets.UTF_8);
        if (channel == ReaderRuntimeManifest.Channel.INTERNAL) {
            Files.writeString(packageRoot.resolve("NON_REDISTRIBUTABLE"),
                    "Internal PathLab validation package. Redistribution is not authorized.\n",
                    StandardCharsets.UTF_8);
        }
        System.out.println(manifest.fingerprint());
        System.out.println(installed);
    }
}
