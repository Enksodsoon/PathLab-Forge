package org.pathlab.forge.conversion;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

public final class BioFormatsEngine implements ConversionEngine {
    private static final int MAX_METADATA_BYTES = 32 * 1024 * 1024;
    private static final Duration INSPECTION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CONVERSION_TIMEOUT = Duration.ofHours(24);
    private final Path runtimeRoot;
    private final java.util.Map<Path, java.util.Map<Integer, FlatSeries>> flattenedSeries =
            new ConcurrentHashMap<>();
    private final java.util.Map<Path, DirectReader> directReaders = new ConcurrentHashMap<>();

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
        return directReader(source).inspect();
    }

    private List<SeriesInfo> inspectConversionMetadata(Path source) throws IOException {
        var executor = Executors.newFixedThreadPool(3, runnable -> {
            var thread = new Thread(runnable, "pathlab-bioformats-metadata");
            thread.setDaemon(true);
            return thread;
        });
        List<SeriesInfo> topLevel;
        List<SeriesInfo> flattened;
        try {
            var topLevelFuture = executor.submit(() -> inspectMetadata(source, true));
            var flattenedFuture = executor.submit(() -> inspectMetadata(source, false));
            var directReaderFuture = executor.submit(() -> directReader(source));
            try {
                topLevel = topLevelFuture.get();
                flattened = flattenedFuture.get();
                directReaderFuture.get();
            } catch (ExecutionException error) {
                var cause = error.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Bio-Formats inspection failed", cause);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Bio-Formats inspection was interrupted", error);
            }
        } finally {
            executor.shutdownNow();
        }
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
        return topLevel.stream()
                .map(item -> item.withResolutionCount(
                        mapping.get(item.index()).resolutions().size()))
                .toList();
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
        convertRegion(request, resolution, crop, output);
    }

    @Override
    public boolean supportsParallelRegions() {
        return available();
    }

    @Override
    public List<Path> convertRegions(
            ConversionRequest request, Path outputDirectory, int workers) throws IOException {
        requireAvailable();
        if (workers < 2) {
            throw new IllegalArgumentException("Parallel rendering requires at least two workers");
        }
        Files.createDirectories(outputDirectory);
        var selected = requireSeries(request.source(), request.seriesIndex());
        var resolution = selectResolution(selected, request.downsample());
        var crop = scaleCrop(request, resolution);
        var regions = planRegions(crop.x(), crop.y(), crop.width(), crop.height(), workers);
        var executor = Executors.newFixedThreadPool(regions.size(), runnable -> {
            var thread = new Thread(runnable, "pathlab-bioformats-region");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<Path>>();
            for (var index = 0; index < regions.size(); index++) {
                var region = regions.get(index);
                var output = outputDirectory.resolve("region-%02d.ome.tif".formatted(index));
                tasks.add(() -> {
                    convertRegion(
                            request,
                            resolution,
                            new ScaledCrop(
                                    region.x(),
                                    region.y(),
                                    region.width(),
                                    region.height(),
                                    false),
                            output);
                    return output;
                });
            }
            var futures = executor.invokeAll(tasks);
            var outputs = new ArrayList<Path>(futures.size());
            for (var future : futures) {
                try {
                    outputs.add(future.get());
                } catch (ExecutionException error) {
                    var cause = error.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException("Parallel Bio-Formats rendering failed", cause);
                }
            }
            return List.copyOf(outputs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Parallel Bio-Formats rendering was interrupted", error);
        } finally {
            executor.shutdownNow();
        }
    }

    static List<RenderRegion> planRegions(
            int x, int y, int width, int height, int requestedWorkers) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || requestedWorkers <= 0) {
            throw new IllegalArgumentException("Parallel render geometry is invalid");
        }
        var workers = Math.min(requestedWorkers, height);
        var baseHeight = height / workers;
        var remainder = height % workers;
        var regions = new ArrayList<RenderRegion>(workers);
        var nextY = y;
        for (var index = 0; index < workers; index++) {
            var regionHeight = baseHeight + (index < remainder ? 1 : 0);
            regions.add(new RenderRegion(x, nextY, width, regionHeight));
            nextY += regionHeight;
        }
        return List.copyOf(regions);
    }

    private void convertRegion(
            ConversionRequest request, Resolution resolution, ScaledCrop crop, Path output)
            throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        var arguments = new ArrayList<>(List.of(
                "-no-upgrade",
                "-series",
                Integer.toString(resolution.readerIndex()),
                "-merge",
                "-expand",
                "-bigtiff",
                "-compression",
                "JPEG",
                "-quality",
                "0.95",
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
                commandWithHeap(
                        "640m",
                        "loci.formats.tools.ImageConverter",
                        arguments),
                CONVERSION_TIMEOUT,
                4 * 1024 * 1024);
        if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("Bio-Formats conversion failed: " + tail(result.output()));
        }
    }

    @Override
    public PreviewSource renderPreview(
            Path source, int seriesIndex, Path output, int maxDimension) throws IOException {
        requireAvailable();
        if (maxDimension < 512) {
            throw new IllegalArgumentException("Preview bound is too small");
        }
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        var selected = requireSeries(source, seriesIndex);
        var resolution = selected.resolutions().stream()
                .filter(item -> item.width() <= maxDimension && item.height() <= maxDimension)
                .max(java.util.Comparator.comparingLong(
                        item -> (long) item.width() * item.height()))
                .orElseGet(() -> selected.resolutions().stream()
                        .min(java.util.Comparator.comparingLong(
                                item -> (long) item.width() * item.height()))
                        .orElseThrow());
        var arguments = new ArrayList<>(List.of(
                "-no-upgrade",
                "-series",
                Integer.toString(resolution.readerIndex()),
                "-merge",
                "-expand",
                "-bigtiff",
                "-compression",
                "LZW",
                "-no-sas",
                "-option",
                "cellsens.fail_on_missing_ets",
                "true",
                source.toString(),
                output.toString()));
        var result = run(
                command("loci.formats.tools.ImageConverter", arguments),
                CONVERSION_TIMEOUT,
                4 * 1024 * 1024);
        if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("Bio-Formats preview failed: " + tail(result.output()));
        }
        return new PreviewSource(output, resolution.width(), resolution.height());
    }

    @Override
    public boolean supportsDirectTiles() {
        return available();
    }

    @Override
    public DirectTileSource directTileSource(Path source, int seriesIndex) throws IOException {
        var selected = directReader(source).series(seriesIndex);
        return new DirectTileSource(selected.width(), selected.height(), 512);
    }

    @Override
    public byte[] readDirectTile(
            Path source, int seriesIndex, int level, int tileX, int tileY)
            throws IOException {
        if (tileX < 0 || tileY < 0 || level < 0) {
            throw new IllegalArgumentException("Direct tile coordinates are invalid");
        }
        var reader = directReader(source);
        var selected = reader.series(seriesIndex);
        var tileSource = new DirectTileSource(selected.width(), selected.height(), 512);
        if (level > tileSource.maximumLevel()) {
            throw new IllegalArgumentException("Direct tile level is invalid");
        }
        var scale = Math.scalb(1.0, tileSource.maximumLevel() - level);
        var levelWidth = Math.max(1, (int) Math.ceil(selected.width() / scale));
        var levelHeight = Math.max(1, (int) Math.ceil(selected.height() / scale));
        var outputX = Math.multiplyExact(tileX, tileSource.tileSize());
        var outputY = Math.multiplyExact(tileY, tileSource.tileSize());
        if (outputX >= levelWidth || outputY >= levelHeight) {
            throw new IllegalArgumentException("Direct tile is outside the image");
        }
        var outputWidth = Math.min(tileSource.tileSize(), levelWidth - outputX);
        var outputHeight = Math.min(tileSource.tileSize(), levelHeight - outputY);
        var resolution = selectDirectResolution(selected, scale);
        var sourceX = (int) Math.floor((double) outputX * resolution.width() / levelWidth);
        var sourceY = (int) Math.floor((double) outputY * resolution.height() / levelHeight);
        var sourceRight = (int) Math.ceil(
                (double) (outputX + outputWidth) * resolution.width() / levelWidth);
        var sourceBottom = (int) Math.ceil(
                (double) (outputY + outputHeight) * resolution.height() / levelHeight);
        var sourceWidth = Math.max(1, Math.min(resolution.width() - sourceX, sourceRight - sourceX));
        var sourceHeight =
                Math.max(1, Math.min(resolution.height() - sourceY, sourceBottom - sourceY));
        var image = reader.read(
                seriesIndex,
                resolution.readerIndex(),
                sourceX,
                sourceY,
                sourceWidth,
                sourceHeight);
        if (image.getWidth() != outputWidth || image.getHeight() != outputHeight) {
            image = resize(image, outputWidth, outputHeight);
        }
        return encodeJpeg(image, 0.92f);
    }

    @Override
    public byte[] seriesThumbnail(Path source, int seriesIndex, int maxDimension)
            throws IOException {
        if (maxDimension < 96 || maxDimension > 1024) {
            throw new IllegalArgumentException("Thumbnail bound is invalid");
        }
        var reader = directReader(source);
        var selected = reader.series(seriesIndex);
        var resolution = selected.resolutions().stream()
                .min(java.util.Comparator.comparingLong(
                        item -> (long) item.width() * item.height()))
                .orElseThrow(() -> new IOException("Series has no readable resolutions"));
        var image = reader.read(
                seriesIndex,
                resolution.readerIndex(),
                0,
                0,
                resolution.width(),
                resolution.height());
        var scale = Math.min(
                1.0,
                (double) maxDimension / Math.max(image.getWidth(), image.getHeight()));
        var width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        var height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        if (width != image.getWidth() || height != image.getHeight()) {
            image = resize(image, width, height);
        }
        return encodeJpeg(image, 0.88f);
    }

    private FlatSeries requireSeries(Path source, int seriesIndex) throws IOException {
        var sourceKey = source.toAbsolutePath().normalize();
        var selected = flattenedSeries.getOrDefault(sourceKey, Map.of()).get(seriesIndex);
        if (selected == null) {
            inspectConversionMetadata(source);
            selected = flattenedSeries.getOrDefault(sourceKey, Map.of()).get(seriesIndex);
        }
        if (selected == null) {
            throw new IOException("Selected image series is no longer available");
        }
        return selected;
    }

    private static Resolution selectResolution(FlatSeries series, double downsample)
            throws IOException {
        var selected = series.resolutions().stream()
                .filter(resolution ->
                        (double) series.width() / resolution.width() <= downsample + 0.01)
                .max(java.util.Comparator.comparingDouble(
                        resolution -> (double) series.width() / resolution.width()))
                .or(() -> series.resolutions().stream().max(
                        java.util.Comparator.comparingLong(
                                resolution -> (long) resolution.width() * resolution.height())))
                .orElseThrow(() -> new IOException("Series has no readable resolutions"));
        return selected;
    }

    private static Resolution selectDirectResolution(FlatSeries series, double downsample)
            throws IOException {
        return series.resolutions().stream()
                .filter(resolution ->
                        (double) series.width() / resolution.width() <= downsample + 0.05)
                .max(java.util.Comparator.comparingDouble(
                        resolution -> (double) series.width() / resolution.width()))
                .or(() -> series.resolutions().stream().max(
                        java.util.Comparator.comparingLong(
                                resolution -> (long) resolution.width() * resolution.height())))
                .orElseThrow(() -> new IOException("Series has no readable resolutions"));
    }

    private static ScaledCrop scaleCrop(
            ConversionRequest request, Resolution resolution) {
        var scaleX = (double) resolution.width() / request.seriesWidth();
        var scaleY = (double) resolution.height() / request.seriesHeight();
        var width = Math.min(
                resolution.width(), Math.max(1, (int) Math.round(request.cropWidth() * scaleX)));
        var height = Math.min(
                resolution.height(), Math.max(1, (int) Math.round(request.cropHeight() * scaleY)));
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
        return commandWithHeap(null, mainClass, arguments);
    }

    private List<String> commandWithHeap(
            String maximumHeap, String mainClass, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        isWindows() ? "java.exe" : "java")
                .toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        if (maximumHeap != null) {
            command.add("-Xmx" + maximumHeap);
        }
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

    private synchronized DirectReader directReader(Path source) throws IOException {
        var key = source.toAbsolutePath().normalize();
        var existing = directReaders.get(key);
        if (existing != null) {
            return existing;
        }
        try {
            var opened = new DirectReader(runtimeRoot.resolve("bioformats_package.jar"), key);
            directReaders.put(key, opened);
            return opened;
        } catch (ReflectiveOperationException error) {
            throw new IOException("Bio-Formats direct viewer could not open the slide", error);
        }
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        var resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        var output = new ByteArrayOutputStream();
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer is unavailable");
        }
        var writer = writers.next();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
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

    private static final class DirectReader {
        private final URLClassLoader loader;
        private final Object reader;
        private final Class<?> readerClass;
        private final Object metadata;

        private DirectReader(Path jar, Path source)
                throws ReflectiveOperationException, IOException {
            loader = new URLClassLoader(
                    new java.net.URL[] {jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            quietThirdPartyLogging(loader);
            readerClass = Class.forName("loci.formats.ImageReader", true, loader);
            reader = readerClass.getConstructor().newInstance();
            var metadataTools = Class.forName("loci.formats.MetadataTools", true, loader);
            metadata = metadataTools.getMethod("createOMEXMLMetadata").invoke(null);
            var metadataStore = Class.forName("loci.formats.meta.MetadataStore", true, loader);
            readerClass.getMethod("setMetadataStore", metadataStore).invoke(reader, metadata);
            invoke("setFlattenedResolutions", new Class<?>[] {boolean.class}, false);
            invoke("setId", new Class<?>[] {String.class}, source.toString());
        }

        private static void quietThirdPartyLogging(ClassLoader loader) {
            try {
                var loggerFactory = Class.forName("org.slf4j.LoggerFactory", true, loader);
                var context = loggerFactory.getMethod("getILoggerFactory").invoke(null);
                var logger = context.getClass().getMethod("getLogger", String.class)
                        .invoke(context, "ROOT");
                var levelClass = Class.forName("ch.qos.logback.classic.Level", true, loader);
                var warn = levelClass.getField("WARN").get(null);
                logger.getClass().getMethod("setLevel", levelClass).invoke(logger, warn);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Logging is optional; reader availability must not depend on its implementation.
            }
        }

        private synchronized List<SeriesInfo> inspect() throws IOException {
            try {
                var count = (int) invoke("getSeriesCount", new Class<?>[0]);
                var result = new ArrayList<SeriesInfo>(count);
                for (var seriesIndex = 0; seriesIndex < count; seriesIndex++) {
                    invoke("setSeries", new Class<?>[] {int.class}, seriesIndex);
                    var scale = physicalScale(seriesIndex);
                    result.add(new SeriesInfo(
                            seriesIndex,
                            imageName(seriesIndex),
                            (int) invoke("getSizeX", new Class<?>[0]),
                            (int) invoke("getSizeY", new Class<?>[0]),
                            (int) invoke("getSizeC", new Class<?>[0]),
                            (int) invoke("getSizeZ", new Class<?>[0]),
                            (int) invoke("getSizeT", new Class<?>[0]),
                            pixelType(),
                            scale.x(),
                            scale.y(),
                            scale.unit(),
                            (int) invoke("getResolutionCount", new Class<?>[0])));
                }
                return List.copyOf(result);
            } catch (ReflectiveOperationException error) {
                throw new IOException("Bio-Formats could not inspect the slide", error);
            }
        }

        private String imageName(int seriesIndex) {
            try {
                var value = metadata.getClass()
                        .getMethod("getImageName", int.class)
                        .invoke(metadata, seriesIndex);
                if (value instanceof String name && !name.isBlank()) {
                    return name;
                }
            } catch (ReflectiveOperationException ignored) {
                // A stable fallback is sufficient when the format omits an image name.
            }
            return "Image " + (seriesIndex + 1);
        }

        private String pixelType() {
            try {
                var type = (int) invoke("getPixelType", new Class<?>[0]);
                var formatTools = Class.forName("loci.formats.FormatTools", true, loader);
                return (String) formatTools
                        .getMethod("getPixelTypeString", int.class)
                        .invoke(null, type);
            } catch (ReflectiveOperationException error) {
                return "";
            }
        }

        private PhysicalScale physicalScale(int seriesIndex) {
            try {
                var x = metadata.getClass()
                        .getMethod("getPixelsPhysicalSizeX", int.class)
                        .invoke(metadata, seriesIndex);
                var y = metadata.getClass()
                        .getMethod("getPixelsPhysicalSizeY", int.class)
                        .invoke(metadata, seriesIndex);
                if (x == null || y == null) {
                    return PhysicalScale.UNKNOWN;
                }
                var xValue = ((Number) x.getClass().getMethod("value").invoke(x)).doubleValue();
                var yValue = ((Number) y.getClass().getMethod("value").invoke(y)).doubleValue();
                var unit = x.getClass().getMethod("unit").invoke(x);
                var symbol = String.valueOf(
                        unit.getClass().getMethod("getSymbol").invoke(unit));
                return new PhysicalScale(xValue, yValue, symbol);
            } catch (ReflectiveOperationException | ClassCastException error) {
                return PhysicalScale.UNKNOWN;
            }
        }

        private synchronized FlatSeries series(int series) throws IOException {
            try {
                invoke("setSeries", new Class<?>[] {int.class}, series);
                var width = (int) invoke("getSizeX", new Class<?>[0]);
                var height = (int) invoke("getSizeY", new Class<?>[0]);
                var count = (int) invoke("getResolutionCount", new Class<?>[0]);
                var resolutions = new ArrayList<Resolution>();
                for (var resolution = 0; resolution < count; resolution++) {
                    invoke("setResolution", new Class<?>[] {int.class}, resolution);
                    resolutions.add(new Resolution(
                            resolution,
                            (int) invoke("getSizeX", new Class<?>[0]),
                            (int) invoke("getSizeY", new Class<?>[0])));
                }
                invoke("setResolution", new Class<?>[] {int.class}, 0);
                return new FlatSeries(width, height, List.copyOf(resolutions));
            } catch (ReflectiveOperationException error) {
                throw new IOException("Bio-Formats could not inspect the selected series", error);
            }
        }

        private synchronized BufferedImage read(
                int series,
                int resolution,
                int x,
                int y,
                int width,
                int height)
                throws IOException {
            try {
                invoke("setSeries", new Class<?>[] {int.class}, series);
                invoke("setResolution", new Class<?>[] {int.class}, resolution);
                var bits = (int) invoke("getBitsPerPixel", new Class<?>[0]);
                var channels = (int) invoke("getRGBChannelCount", new Class<?>[0]);
                var interleaved = (boolean) invoke("isInterleaved", new Class<?>[0]);
                if (bits > 8 || channels < 3) {
                    throw new IOException("Direct viewer requires an 8-bit RGB series");
                }
                var bytes = (byte[]) invoke(
                        "openBytes",
                        new Class<?>[] {
                            int.class, int.class, int.class, int.class, int.class
                        },
                        0,
                        x,
                        y,
                        width,
                        height);
                var pixels = Math.multiplyExact(width, height);
                if (bytes.length < Math.multiplyExact(pixels, 3)) {
                    throw new IOException("Bio-Formats returned an incomplete RGB tile");
                }
                var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                var rgb = new int[pixels];
                for (var index = 0; index < pixels; index++) {
                    var red = bytes[interleaved ? index * channels : index] & 0xff;
                    var green = bytes[interleaved ? index * channels + 1 : pixels + index] & 0xff;
                    var blue = bytes[interleaved ? index * channels + 2 : pixels * 2 + index] & 0xff;
                    rgb[index] = (red << 16) | (green << 8) | blue;
                }
                image.setRGB(0, 0, width, height, rgb, 0, width);
                return image;
            } catch (ReflectiveOperationException error) {
                throw new IOException("Bio-Formats could not read the requested tile", error);
            }
        }

        private Object invoke(String name, Class<?>[] parameters, Object... arguments)
                throws ReflectiveOperationException {
            try {
                return readerClass.getMethod(name, parameters).invoke(reader, arguments);
            } catch (InvocationTargetException error) {
                var cause = error.getCause();
                if (cause instanceof Exception exception) {
                    throw new ReflectiveOperationException(exception);
                }
                throw error;
            }
        }
    }

    private record PhysicalScale(double x, double y, String unit) {
        private static final PhysicalScale UNKNOWN = new PhysicalScale(0, 0, "");
    }
}
