package org.pathlab.forge.derivative;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.pathlab.forge.conversion.ConversionRequest;

public final class VipsRuntime implements DerivativeEngine {
    private static final Duration OPERATION_TIMEOUT = Duration.ofHours(24);
    private final Path executable;

    private VipsRuntime(Path executable) {
        this.executable = executable;
    }

    public static VipsRuntime discover(Path dataRoot) {
        var candidates = new ArrayList<Path>();
        var configured = System.getProperty("pathlab.forge.vips");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PATHLAB_FORGE_VIPS");
        }
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        candidates.add(dataRoot.resolve("runtime").resolve("vips"));
        candidates.add(Path.of(
                System.getProperty("user.home"),
                ".cache",
                "pathlab-libvips-8.18.2",
                "extract",
                "vips-dev-8.18",
                "bin"));
        for (var candidate : candidates) {
            var found = findExecutable(candidate);
            if (found != null) {
                return new VipsRuntime(found);
            }
        }
        return new VipsRuntime(null);
    }

    @Override
    public boolean available() {
        return executable != null;
    }

    @Override
    public String description() {
        return available() ? "libvips 8.18 local runtime" : "libvips runtime not installed";
    }

    @Override
    public boolean supportsOmeRendering() {
        return available();
    }

    @Override
    public void renderOme(ConversionRequest request, Path output) throws IOException {
        requireAvailable();
        var cropped = output.resolveSibling("crop.partial.v");
        Files.deleteIfExists(cropped);
        try {
            run(List.of(
                    "crop",
                    request.source().toString(),
                    cropped.toString(),
                    Integer.toString(request.cropX()),
                    Integer.toString(request.cropY()),
                    Integer.toString(request.cropWidth()),
                    Integer.toString(request.cropHeight())));
            run(List.of(
                    "thumbnail",
                    cropped.toString(),
                    output.toString(),
                    Integer.toString(request.outputWidth()),
                    "--height",
                    Integer.toString(request.outputHeight()),
                    "--size",
                    "force"));
            requireNonempty(output, "rendered OME-TIFF");
        } finally {
            Files.deleteIfExists(cropped);
        }
    }

    @Override
    public void assembleRegions(List<Path> regions, Path renderedOme) throws IOException {
        requireAvailable();
        if (regions.size() < 2) {
            throw new IllegalArgumentException("At least two rendered regions are required");
        }
        for (var region : regions) {
            requireNonempty(region, "rendered RGB region");
        }
        run(List.of(
                "arrayjoin",
                serializeImageArray(regions),
                renderedOme + "[tile,tile-width=512,tile-height=512,"
                        + "compression=jpeg,Q=95,bigtiff]",
                "--across",
                "1"));
        requireNonempty(renderedOme, "assembled rendered OME-TIFF");
    }

    @Override
    public boolean supportsDirectFinalOme() {
        return available();
    }

    @Override
    public void assembleRegionsFinal(
            List<Path> regions, Path pyramidalOme, int width, int height) throws IOException {
        requireAvailable();
        if (regions.size() < 2 || width < 1 || height < 1) {
            throw new IllegalArgumentException("Direct final OME geometry is invalid");
        }
        for (var region : regions) {
            requireNonempty(region, "rendered RGB region");
        }
        run(List.of(
                "arrayjoin",
                serializeImageArray(regions),
                pyramidalOme + "[pyramid,tile,tile-width=512,tile-height=512,"
                        + "compression=jpeg,Q=" + omeJpegQuality(width, height)
                        + ",bigtiff,subifd]",
                "--across",
                "1"));
        requireNonempty(pyramidalOme, "final pyramidal OME-TIFF");
    }

    static String serializeImageArray(List<Path> paths) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Image array is empty");
        }
        return paths.stream()
                .map(path -> path.toAbsolutePath().normalize().toString().replace('\\', '/'))
                .map(path -> path.replace(" ", "\\ "))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    @Override
    public void optimizeOme(Path renderedOme, Path pyramidalOme, int width, int height)
            throws IOException {
        requireAvailable();
        var jpegQuality = omeJpegQuality(width, height);
        run(List.of(
                "thumbnail",
                renderedOme.toString(),
                pyramidalOme + "[pyramid,tile,tile-width=512,tile-height=512,"
                        + "compression=jpeg,Q=" + jpegQuality + ",bigtiff,subifd]",
                Integer.toString(width),
                "--height",
                Integer.toString(height),
                "--size",
                "force"));
        requireNonempty(pyramidalOme, "pyramidal OME-TIFF");
    }

    static int omeJpegQuality(int width, int height) {
        var quality = Integer.getInteger("pathlab.forge.ome.jpegQuality", 93);
        if (!List.of(75, 80, 85, 90, 93).contains(quality)) {
            throw new IllegalArgumentException(
                    "OME JPEG quality must be one of 75, 80, 85, 90 or 93");
        }
        return quality;
    }

    @Override
    public DerivativeInfo generateDzi(
            Path omeTiff, Path outputRoot, int width, int height) throws IOException {
        requireAvailable();
        Files.createDirectories(outputRoot);
        run(List.of(
                "dzsave",
                omeTiff.toString(),
                outputRoot.resolve("slide").toString(),
                "--layout",
                "dz",
                "--tile-size",
                "512",
                "--overlap",
                "1",
                "--suffix",
                ".jpg[Q=95,strip,optimize_coding]",
                "--depth",
                "onepixel",
                "--region-shrink",
                "mean",
                "--skip-blanks",
                "-1"));
        run(List.of(
                "thumbnail",
                omeTiff.toString(),
                outputRoot.resolve("thumbnail.jpg[Q=82,strip,optimize_coding]").toString(),
                "640",
                "--size",
                "down"));
        Files.deleteIfExists(outputRoot.resolve("slide_files").resolve("vips-properties.xml"));
        return DziValidator.validate(outputRoot, width, height);
    }

    private void run(List<String> arguments) throws IOException {
        var command = commandLine(executable, arguments);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        var currentPath = builder.environment().getOrDefault("PATH", "");
        builder.environment().put(
                "PATH", executable.getParent() + java.io.File.pathSeparator + currentPath);
        var process = builder.start();
        var output = new ByteArrayOutputStream();
        var reader = new Thread(
                () -> copyBounded(process.getInputStream(), output, process),
                "pathlab-vips-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("libvips operation timed out");
            }
            reader.join(10_000);
            if (process.exitValue() != 0) {
                throw new IOException("libvips operation failed: " + tail(
                        output.toString(StandardCharsets.UTF_8)));
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("libvips operation was interrupted", error);
        }
    }

    static List<String> commandLine(Path executable, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.add("--vips-concurrency=4");
        command.add("--vips-cache-max-memory=805306368");
        command.add("--vips-cache-max-files=128");
        command.add("--vips-cache-max=100");
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static void copyBounded(
            InputStream input, ByteArrayOutputStream output, Process process) {
        try (input; output) {
            var buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 4 * 1024 * 1024) {
                    process.destroyForcibly();
                    return;
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            process.destroyForcibly();
        }
    }

    private static Path findExecutable(Path candidate) {
        if (Files.isRegularFile(candidate)) {
            return candidate.toAbsolutePath().normalize();
        }
        if (!Files.isDirectory(candidate)) {
            return null;
        }
        var expected = java.io.File.separatorChar == '\\' ? "vips.exe" : "vips";
        try (Stream<Path> paths = Files.find(
                candidate,
                3,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equalsIgnoreCase(expected))) {
            return paths.findFirst().map(path -> path.toAbsolutePath().normalize()).orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException(
                    "Install libvips locally or set PATHLAB_FORGE_VIPS");
        }
    }

    private static void requireNonempty(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            throw new IOException(label + " was not created");
        }
    }

    private static String tail(String value) {
        var normalized = value.strip();
        return normalized.length() <= 800
                ? normalized
                : normalized.substring(normalized.length() - 800);
    }
}
