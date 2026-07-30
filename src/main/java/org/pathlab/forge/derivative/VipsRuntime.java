package org.pathlab.forge.derivative;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    public void validateOmeGeometry(Path omeTiff, int width, int height) throws IOException {
        var header = executable.resolveSibling(
                java.io.File.separatorChar == '\\' ? "vipsheader.exe" : "vipsheader");
        var actualWidth = parseIntegerOutput(runCommand(
                List.of(header.toString(), "-f", "width", omeTiff.toString())));
        var actualHeight = parseIntegerOutput(runCommand(
                List.of(header.toString(), "-f", "height", omeTiff.toString())));
        if (actualWidth != width || actualHeight != height) {
            throw new IOException(
                    "OME geometry mismatch: expected "
                            + width + "x" + height
                            + " but found " + actualWidth + "x" + actualHeight);
        }
    }

    @Override
    public void assembleRegionsFinal(
            List<Path> regions, Path pyramidalOme, int width, int height) throws IOException {
        assembleRegionsFinal(regions, pyramidalOme, width, height, 1.0);
    }

    @Override
    public void assembleRegionsFinal(
            List<Path> regions,
            Path pyramidalOme,
            int width,
            int height,
            double downsample)
            throws IOException {
        requireAvailable();
        if (regions.size() < 2 || width < 1 || height < 1) {
            throw new IllegalArgumentException("Direct final OME geometry is invalid");
        }
        for (var region : regions) {
            requireNonempty(region, "rendered RGB region");
        }
        var prepared = regions;
        var resizedRoot = pyramidalOme.resolveSibling("resized-regions.partial");
        var paddedJoin = pyramidalOme.resolveSibling("joined-resized.partial.tif");
        try {
            Files.deleteIfExists(paddedJoin);
            boolean uniformTargetHeights = true;
            if (downsample != 1.0) {
                deleteTree(resizedRoot);
                Files.createDirectories(resizedRoot);
                var resized = new ArrayList<Path>(regions.size());
                var sourceHeights = new ArrayList<Integer>(regions.size());
                for (var region : regions) {
                    sourceHeights.add(imageDimension(region, "height"));
                }
                var targetHeights = targetRegionHeights(sourceHeights, height);
                uniformTargetHeights = targetHeights.stream().distinct().count() == 1;
                for (var index = 0; index < regions.size(); index++) {
                    var targetHeight = targetHeights.get(index);
                    var output = resizedRoot.resolve("region-%02d.tif".formatted(index));
                    run(List.of(
                            "thumbnail",
                            regions.get(index).toString(),
                            output + "[tile,tile-width=512,tile-height=512,"
                                    + "compression=jpeg,Q=95,bigtiff,properties=false]",
                            Integer.toString(width),
                            "--height",
                            Integer.toString(targetHeight),
                            "--size",
                            "force"));
                    resized.add(output);
                }
                prepared = List.copyOf(resized);
            }
            if (uniformTargetHeights) {
                run(List.of(
                        "arrayjoin",
                        serializeImageArray(prepared),
                        pyramidalOme + "[pyramid,tile,tile-width=512,tile-height=512,"
                                + "compression=jpeg,Q=" + omeJpegQuality(width, height)
                                + ",bigtiff,subifd]",
                        "--across",
                        "1"));
            } else {
                run(List.of(
                        "arrayjoin",
                        serializeImageArray(prepared),
                        paddedJoin + "[tile,tile-width=512,tile-height=512,"
                                + "compression=jpeg,Q=95,bigtiff]",
                        "--across",
                        "1"));
                run(List.of(
                        "crop",
                        paddedJoin.toString(),
                        pyramidalOme + "[pyramid,tile,tile-width=512,tile-height=512,"
                                + "compression=jpeg,Q=" + omeJpegQuality(width, height)
                                + ",bigtiff,subifd]",
                        "0",
                        "0",
                        Integer.toString(width),
                        Integer.toString(height)));
            }
            requireNonempty(pyramidalOme, "final pyramidal OME-TIFF");
        } finally {
            Files.deleteIfExists(paddedJoin);
            deleteTree(resizedRoot);
        }
    }

    static List<Integer> targetRegionHeights(List<Integer> sourceHeights, int targetHeight) {
        if (sourceHeights.isEmpty()
                || targetHeight < sourceHeights.size()
                || sourceHeights.stream().anyMatch(height -> height == null || height < 1)) {
            throw new IllegalArgumentException("Region resample geometry is invalid");
        }
        var totalSourceHeight = sourceHeights.stream()
                .mapToLong(Integer::longValue)
                .reduce(0, Math::addExact);
        var targetHeights = new ArrayList<Integer>(sourceHeights.size());
        long cumulativeSourceHeight = 0;
        int previousTargetBottom = 0;
        for (var index = 0; index < sourceHeights.size(); index++) {
            cumulativeSourceHeight = Math.addExact(
                    cumulativeSourceHeight, sourceHeights.get(index));
            var targetBottom = index == sourceHeights.size() - 1
                    ? targetHeight
                    : Math.toIntExact(Math.round(
                            (double) cumulativeSourceHeight * targetHeight
                                    / totalSourceHeight));
            var regionHeight = targetBottom - previousTargetBottom;
            if (regionHeight < 1) {
                throw new IllegalArgumentException("Resampled region height is invalid");
            }
            targetHeights.add(regionHeight);
            previousTargetBottom = targetBottom;
        }
        return List.copyOf(targetHeights);
    }

    private int imageDimension(Path image, String field) throws IOException {
        var header = executable.resolveSibling(
                java.io.File.separatorChar == '\\' ? "vipsheader.exe" : "vipsheader");
        return parseIntegerOutput(runCommand(
                List.of(header.toString(), "-f", field, image.toString())));
    }

    static int parseIntegerOutput(String output) throws IOException {
        var lines = output.lines().toList();
        for (var index = lines.size() - 1; index >= 0; index--) {
            var value = lines.get(index).strip();
            if (value.matches("[0-9]+")) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException error) {
                    throw new IOException("libvips numeric output is outside the supported range", error);
                }
            }
        }
        throw new IOException("libvips did not return a numeric image property: " + tail(output));
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
        var configured = System.getProperty("pathlab.forge.ome.jpegQuality");
        var quality = configured == null
                ? (long) width * height >= 1_000_000_000L ? 75 : 93
                : Integer.parseInt(configured);
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
        var probe = outputRoot.resolve("quality-probe.png");
        var candidates = new LinkedHashMap<Integer, Path>();
        try {
            run(List.of(
                    "thumbnail",
                    omeTiff.toString(),
                    probe.toString(),
                    "1024",
                    "--size",
                    "down"));
            for (var quality : List.of(85, 90, 95)) {
                var candidate = outputRoot.resolve("quality-candidate-" + quality + ".jpg");
                candidates.put(quality, candidate);
                run(List.of(
                        "copy",
                        probe.toString(),
                        candidate + "[Q=" + quality + ",strip]"));
            }
            var selection = AdaptiveJpegQualitySelector.select(probe, candidates);
            Files.deleteIfExists(probe);
            for (var candidate : candidates.values()) {
                Files.deleteIfExists(candidate);
            }
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
                    ".jpg[Q=" + selection.quality() + ",strip]",
                    "--depth",
                    "onepixel",
                    "--region-shrink",
                    "mean",
                    "--skip-blanks",
                    "-1"));
            run(List.of(
                    "thumbnail",
                    omeTiff.toString(),
                    outputRoot.resolve("thumbnail.jpg[Q=82,strip]").toString(),
                    "640",
                    "--size",
                    "down"));
            Files.deleteIfExists(outputRoot.resolve("slide_files").resolve("vips-properties.xml"));
            var validated = DziValidator.validate(outputRoot, width, height);
            return new DerivativeInfo(
                    validated.root(),
                    validated.bytes(),
                    validated.fileCount(),
                    validated.tileCount(),
                    validated.sha256(),
                    validated.ledger(),
                    selection.quality(),
                    selection.minimumWindowedSsim(),
                    selection.meanDeltaE00());
        } finally {
            Files.deleteIfExists(probe);
            for (var candidate : candidates.values()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private String run(List<String> arguments) throws IOException {
        var command = commandLine(executable, arguments);
        return runCommand(command);
    }

    private String runCommand(List<String> command) throws IOException {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        var currentPath = builder.environment().getOrDefault("PATH", "");
        builder.environment().put(
                "PATH", executable.getParent() + java.io.File.pathSeparator + currentPath);
        var process = org.pathlab.forge.runtime.ChildProcessContainment.global()
                .register(builder.start());
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
            return output.toString(StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("libvips operation was interrupted", error);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static List<String> commandLine(Path executable, List<String> arguments) {
        var profile = org.pathlab.forge.runtime.RuntimeProfile.target();
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.add("--vips-concurrency=" + profile.vipsConcurrency());
        command.add("--vips-cache-max-memory=" + profile.vipsCacheBytes());
        command.add("--vips-cache-max-files=" + profile.vipsCacheFiles());
        command.add("--vips-cache-max=" + profile.vipsCacheOperations());
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
