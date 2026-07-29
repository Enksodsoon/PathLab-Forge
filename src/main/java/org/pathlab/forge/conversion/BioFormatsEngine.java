package org.pathlab.forge.conversion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class BioFormatsEngine implements ConversionEngine {
    private static final int MAX_METADATA_BYTES = 32 * 1024 * 1024;
    private static final Duration INSPECTION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CONVERSION_TIMEOUT = Duration.ofHours(24);
    private final Path runtimeRoot;
    private final java.util.Map<Path, java.util.Map<Integer, FlatSeries>> flattenedSeries =
            new ConcurrentHashMap<>();

    private BioFormatsEngine(Path runtimeRoot) {
        this.runtimeRoot = runtimeRoot;
    }

    public static BioFormatsEngine discover(Path dataRoot) {
        var candidates = new ArrayList<Path>();
        var configured = System.getProperty("pathlab.forge.bftools");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PATHLAB_FORGE_BFTOOLS");
        }
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        candidates.add(dataRoot.resolve("runtime").resolve("bftools"));
        candidates.add(Path.of(System.getProperty("java.io.tmpdir"), "pathlab-bftools-probe"));
        for (var candidate : candidates) {
            var root = findRuntime(candidate);
            if (root != null) {
                return new BioFormatsEngine(root);
            }
        }
        return new BioFormatsEngine(null);
    }

    @Override
    public boolean available() {
        return runtimeRoot != null;
    }

    @Override
    public String runtimeDescription() {
        return available() ? "Bio-Formats 8.5 local runtime" : "Bio-Formats runtime not installed";
    }

    @Override
    public List<SeriesInfo> inspect(Path source) throws IOException {
        requireAvailable();
        var topLevel = inspectMetadata(source, true);
        var flattened = inspectMetadata(source, false);
        var matched = new ArrayList<MatchedTop>();
        for (var top : topLevel) {
            var full = flattened.stream()
                    .filter(item -> item.width() == top.width() && item.height() == top.height())
                    .filter(item -> item.name().equals(top.name()))
                    .findFirst()
                    .or(() -> flattened.stream()
                            .filter(item ->
                                    item.width() == top.width() && item.height() == top.height())
                            .findFirst())
                    .orElseThrow(() -> new IOException(
                            "Could not map Bio-Formats series " + top.index()));
            matched.add(new MatchedTop(top, full.index()));
        }
        matched.sort(java.util.Comparator.comparingInt(MatchedTop::readerIndex));
        var mapping = new java.util.HashMap<Integer, FlatSeries>();
        for (var index = 0; index < matched.size(); index++) {
            var item = matched.get(index);
            var end = index + 1 < matched.size()
                    ? matched.get(index + 1).readerIndex()
                    : flattened.size();
            var resolutions = new ArrayList<Resolution>();
            for (var readerIndex = item.readerIndex(); readerIndex < end; readerIndex++) {
                var candidate = flattened.get(readerIndex);
                if (candidate.width() <= item.series().width()
                        && candidate.height() <= item.series().height()
                        && similarAspect(candidate, item.series())) {
                    resolutions.add(new Resolution(
                            candidate.index(), candidate.width(), candidate.height()));
                }
            }
            mapping.put(
                    item.series().index(),
                    new FlatSeries(
                            item.series().width(),
                            item.series().height(),
                            List.copyOf(resolutions)));
        }
        flattenedSeries.put(source.toAbsolutePath().normalize(), Map.copyOf(mapping));
        return topLevel;
    }

    @Override
    public void convert(Path source, int seriesIndex, Path output) throws IOException {
        var selected = requireSeries(source, seriesIndex);
        convert(
                new ConversionRequest(
                        source,
                        seriesIndex,
                        0,
                        0,
                        selected.width(),
                        selected.height(),
                        selected.width(),
                        selected.height(),
                        1),
                output);
    }

    @Override
    public void convert(ConversionRequest request, Path output) throws IOException {
        requireAvailable();
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        var selected = requireSeries(request.source(), request.seriesIndex());
        var resolution = selectResolution(selected, request.downsample());
        var crop = scaleCrop(request, resolution);
        var arguments = new ArrayList<>(List.of(
                "-no-upgrade",
                "-series",
                Integer.toString(resolution.readerIndex()),
                "-merge",
                "-expand",
                "-bigtiff",
                "-compression",
                "LZW",
                "-no-sas"));
        if (!crop.fullResolution()) {
            arguments.add("-crop");
            arguments.add(crop.x() + "," + crop.y() + "," + crop.width() + "," + crop.height());
        }
        arguments.addAll(List.of(
                "-option",
                "cellsens.fail_on_missing_ets",
                "true",
                request.source().toString(),
                output.toString()));
        var result = run(
                command(
                        "loci.formats.tools.ImageConverter",
                        arguments),
                CONVERSION_TIMEOUT,
                4 * 1024 * 1024);
        if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("Bio-Formats conversion failed: " + tail(result.output()));
        }
    }

    private FlatSeries requireSeries(Path source, int seriesIndex) throws IOException {
        var sourceKey = source.toAbsolutePath().normalize();
        var selected = flattenedSeries.getOrDefault(sourceKey, Map.of()).get(seriesIndex);
        if (selected == null) {
            inspect(source);
            selected = flattenedSeries.getOrDefault(sourceKey, Map.of()).get(seriesIndex);
        }
        if (selected == null) {
            throw new IOException("Selected image series is no longer available");
        }
        return selected;
    }

    private static Resolution selectResolution(FlatSeries series, int downsample)
            throws IOException {
        var selected = series.resolutions().stream()
                .min(java.util.Comparator.comparingDouble(resolution -> {
                    var scaleX = (double) series.width() / resolution.width();
                    var scaleY = (double) series.height() / resolution.height();
                    return Math.abs(Math.log(scaleX / downsample))
                            + Math.abs(Math.log(scaleY / downsample));
                }))
                .orElseThrow(() -> new IOException("Series has no readable resolutions"));
        var actualScale = (double) series.width() / selected.width();
        if (downsample > 1 && Math.abs(Math.log(actualScale / downsample)) > 0.36) {
            throw new IOException(
                    downsample + "x is not available in this dataset's native pyramid");
        }
        return selected;
    }

    private static ScaledCrop scaleCrop(
            ConversionRequest request, Resolution resolution) {
        var scaleX = (double) resolution.width() / request.seriesWidth();
        var scaleY = (double) resolution.height() / request.seriesHeight();
        var width = Math.min(resolution.width(), request.outputWidth());
        var height = Math.min(resolution.height(), request.outputHeight());
        var x = Math.min(
                resolution.width() - width,
                (int) Math.floor(request.cropX() * scaleX));
        var y = Math.min(
                resolution.height() - height,
                (int) Math.floor(request.cropY() * scaleY));
        var right = x + width;
        var bottom = y + height;
        return new ScaledCrop(
                x,
                y,
                width,
                height,
                x == 0
                        && y == 0
                        && right == resolution.width()
                        && bottom == resolution.height());
    }

    private static boolean similarAspect(SeriesInfo candidate, SeriesInfo top) {
        var candidateAspect = (double) candidate.width() / candidate.height();
        var topAspect = (double) top.width() / top.height();
        return Math.abs(candidateAspect / topAspect - 1) < 0.03;
    }

    private List<String> command(String mainClass, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        isWindows() ? "java.exe" : "java")
                .toString());
        command.add("-cp");
        command.add(runtimeRoot.resolve("bioformats_package.jar").toString());
        command.add(mainClass);
        command.addAll(arguments);
        return command;
    }

    private List<SeriesInfo> inspectMetadata(Path source, boolean noFlat) throws IOException {
        var arguments = new ArrayList<String>();
        if (noFlat) {
            arguments.add("-noflat");
        }
        arguments.addAll(List.of(
                "-nopix", "-novalid", "-omexml-only", source.toString()));
        var result = run(
                command("loci.formats.tools.ImageInfo", arguments),
                INSPECTION_TIMEOUT,
                MAX_METADATA_BYTES);
        if (result.exitCode() != 0) {
            throw new IOException("Bio-Formats inspection failed: " + tail(result.output()));
        }
        try {
            return BioFormatsMetadataParser.parse(extractOmeXml(result.output()));
        } catch (RuntimeException error) {
            throw new IOException("Bio-Formats metadata could not be parsed", error);
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException(
                    "Install Bio-Formats 8.5 locally or set PATHLAB_FORGE_BFTOOLS");
        }
    }

    private static Path findRuntime(Path candidate) {
        if (!Files.isDirectory(candidate)) {
            return null;
        }
        var toolName = isWindows() ? "bfconvert.bat" : "bfconvert";
        try (Stream<Path> paths = Files.find(
                candidate, 2, (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().equalsIgnoreCase(toolName))) {
            return paths.findFirst().map(Path::getParent).orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static ProcessResult run(
            List<String> command, Duration timeout, int outputLimit)
            throws IOException {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new ByteArrayOutputStream();
        var reader = new Thread(
                () -> copyBounded(process.getInputStream(), output, outputLimit, process),
                "pathlab-bioformats-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Bio-Formats process timed out");
            }
            reader.join(10_000);
            return new ProcessResult(
                    process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Bio-Formats process was interrupted", error);
        }
    }

    private static void copyBounded(
            InputStream input, ByteArrayOutputStream output, int limit, Process process) {
        try (input; output) {
            var buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    process.destroyForcibly();
                    return;
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            process.destroyForcibly();
        }
    }

    private static String extractOmeXml(String output) {
        var start = output.indexOf("<?xml");
        if (start < 0) {
            start = output.indexOf("<OME");
        }
        var end = output.lastIndexOf("</OME>");
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("OME-XML was not present in reader output");
        }
        return output.substring(start, end + "</OME>".length());
    }

    private static String tail(String value) {
        var normalized = value.strip();
        return normalized.length() <= 800
                ? normalized
                : normalized.substring(normalized.length() - 800);
    }

    private static boolean isWindows() {
        return java.io.File.separatorChar == '\\';
    }

    private record ProcessResult(int exitCode, String output) {}

    private record MatchedTop(SeriesInfo series, int readerIndex) {}

    private record Resolution(int readerIndex, int width, int height) {}

    private record FlatSeries(int width, int height, List<Resolution> resolutions) {}

    private record ScaledCrop(
            int x, int y, int width, int height, boolean fullResolution) {}
}
