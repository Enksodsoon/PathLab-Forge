package org.pathlab.forge.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.pathlab.forge.reader.DatasetProbe;
import org.pathlab.forge.reader.ImportDiagnostic;
import org.pathlab.forge.reader.ImportProbeException;
import org.pathlab.forge.reader.ReaderDescriptor;

/** Bounded child-process adapter for BSD-licensed libisyntax via the MIT pyisyntax wrapper. */
public final class LibISyntaxEngine implements ConversionEngine {
    private static final int TILE_SIZE = 512;
    private static final int MAX_METADATA_BYTES = 65_536;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 65_536;
    private static final long MAX_REGION_PIXELS = 4_194_304L;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final Path python;
    private final Path runtimeRoot;
    private final Path bridge;
    private final Map<Path, Metadata> metadata = new ConcurrentHashMap<>();

    public static LibISyntaxEngine discover(Path dataRoot) {
        var configuredRoot = configured("pathlab.forge.isyntaxRuntime", "PATHLAB_FORGE_ISYNTAX_RUNTIME");
        var root = configuredRoot == null ? dataRoot.resolve("runtime").resolve("isyntax")
                : Path.of(configuredRoot);
        root = root.toAbsolutePath().normalize();
        var configuredPython = configured("pathlab.forge.isyntaxPython", "PATHLAB_FORGE_ISYNTAX_PYTHON");
        Path python = configuredPython == null ? null : Path.of(configuredPython);
        if (python == null && Files.isRegularFile(root.resolve("python.exe"))) python = root.resolve("python.exe");
        if (python == null && Files.isRegularFile(root.resolve("python-path.txt"))) {
            try {
                var value = Files.readString(root.resolve("python-path.txt"), StandardCharsets.UTF_8).trim();
                if (!value.isBlank()) python = Path.of(value);
            } catch (IOException ignored) { }
        }
        var bridge = root.resolve("isyntax_bridge_v1.py");
        if (!Files.isRegularFile(bridge)) extractBridge(bridge);
        return new LibISyntaxEngine(python, root, bridge);
    }

    LibISyntaxEngine(Path python, Path runtimeRoot, Path bridge) {
        this.python = python == null ? null : python.toAbsolutePath().normalize();
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
        this.bridge = bridge.toAbsolutePath().normalize();
    }

    @Override public boolean available() {
        return python != null && Files.isRegularFile(python) && Files.isRegularFile(bridge)
                && Files.isRegularFile(runtimeRoot.resolve("isyntax").resolve("__init__.py"));
    }

    @Override public String runtimeDescription() {
        return available() ? "libisyntax/pyisyntax 0.1.6 at " + runtimeRoot
                : "libisyntax reader unavailable";
    }

    @Override
    public DatasetProbe.Result probe(Path source) throws IOException, ImportProbeException {
        if (!available()) throw unavailable(source);
        var info = metadata(source);
        return new DatasetProbe.Result(descriptor(info.levels), "Philips iSyntax",
                List.of(source.toAbsolutePath().normalize()), runtimeFingerprint());
    }

    @Override public List<SeriesInfo> inspect(Path source) throws IOException {
        var info = metadata(source);
        return List.of(new SeriesInfo(0, "Philips iSyntax WSI", info.width, info.height,
                3, 1, 1, "uint8", info.mppX, info.mppY, "µm", info.levels));
    }

    @Override public void convert(Path source, int seriesIndex, Path output) throws IOException {
        throw new IOException("iSyntax conversion requires the bounded region pipeline");
    }

    @Override public boolean supportsDirectTiles() { return available(); }

    @Override public DirectTileSource directTileSource(Path source, int seriesIndex) throws IOException {
        requireSeries(seriesIndex);
        var info = metadata(source);
        return new DirectTileSource(info.width, info.height, TILE_SIZE);
    }

    @Override
    public byte[] readDirectTile(Path source, int seriesIndex, int level, int tileX, int tileY)
            throws IOException {
        requireSeries(seriesIndex);
        var info = metadata(source);
        var dzi = new DirectTileSource(info.width, info.height, TILE_SIZE);
        if (level < 0 || level > dzi.maximumLevel() || tileX < 0 || tileY < 0) {
            throw new IllegalArgumentException("Direct tile coordinates are invalid");
        }
        var downsample = Math.scalb(1.0, dzi.maximumLevel() - level);
        var levelWidth = Math.max(1, (int) Math.ceil(info.width / downsample));
        var levelHeight = Math.max(1, (int) Math.ceil(info.height / downsample));
        var outputX = Math.multiplyExact(tileX, TILE_SIZE);
        var outputY = Math.multiplyExact(tileY, TILE_SIZE);
        if (outputX >= levelWidth || outputY >= levelHeight) {
            throw new IllegalArgumentException("Tile is outside the iSyntax image");
        }
        var outputWidth = Math.min(TILE_SIZE, levelWidth - outputX);
        var outputHeight = Math.min(TILE_SIZE, levelHeight - outputY);
        return render(source, "jpeg", (int) Math.floor(outputX * downsample),
                (int) Math.floor(outputY * downsample),
                Math.max(1, (int) Math.ceil(outputWidth * downsample)),
                Math.max(1, (int) Math.ceil(outputHeight * downsample)), outputWidth, outputHeight,
                MAX_IMAGE_BYTES);
    }

    @Override public byte[] seriesThumbnail(Path source, int seriesIndex, int maxDimension)
            throws IOException {
        requireSeries(seriesIndex);
        if (maxDimension < 96 || maxDimension > 1024) {
            throw new IllegalArgumentException("Thumbnail bound is invalid");
        }
        var info = metadata(source);
        var scale = Math.min(1.0, (double) maxDimension / Math.max(info.width, info.height));
        var width = Math.max(1, (int) Math.round(info.width * scale));
        var height = Math.max(1, (int) Math.round(info.height * scale));
        return render(source, "jpeg", 0, 0, info.width, info.height, width, height, MAX_IMAGE_BYTES);
    }

    @Override
    public RgbRegion readRgbRegion(Path source, int seriesIndex, int x, int y, int width, int height)
            throws IOException {
        requireSeries(seriesIndex);
        var info = metadata(source);
        validateRegion(info, x, y, width, height);
        if ((long) width * height > MAX_REGION_PIXELS) throw new IOException("iSyntax region exceeds bounded pixel limit");
        var expected = Math.multiplyExact(Math.multiplyExact(width, height), 3);
        var bytes = render(source, "rgb", x, y, width, height, width, height, expected);
        if (bytes.length != expected) throw new IOException("iSyntax RGB bridge returned an invalid byte count");
        return new RgbRegion(x, y, width, height, bytes);
    }

    @Override public boolean supportsParallelRegions() { return available(); }

    @Override
    public List<Path> convertRegions(ConversionRequest request, Path outputDirectory, int workers,
            BiConsumer<Integer, Integer> progress) throws IOException {
        requireSeries(request.seriesIndex());
        Files.createDirectories(outputDirectory);
        var stripeHeight = Math.max(1, (int) Math.min(request.outputHeight(),
                MAX_REGION_PIXELS / request.outputWidth()));
        var count = (request.outputHeight() + stripeHeight - 1) / stripeHeight;
        var outputs = new ArrayList<Path>(count);
        for (var index = 0; index < count; index++) {
            var outputY = index * stripeHeight;
            var outputHeight = Math.min(stripeHeight, request.outputHeight() - outputY);
            var sourceTop = request.cropY()
                    + (int) Math.floor((double) outputY * request.cropHeight() / request.outputHeight());
            var sourceBottom = request.cropY()
                    + (int) Math.ceil((double) (outputY + outputHeight)
                            * request.cropHeight() / request.outputHeight());
            var bytes = render(request.source(), "png", request.cropX(), sourceTop,
                    request.cropWidth(), Math.max(1, sourceBottom - sourceTop),
                    request.outputWidth(), outputHeight, MAX_IMAGE_BYTES);
            var output = outputDirectory.resolve("isyntax-%05d.png".formatted(index));
            Files.write(output, bytes);
            outputs.add(output);
            progress.accept(index + 1, count);
        }
        return List.copyOf(outputs);
    }

    @Override public void closeDirectSource(Path source) {
        metadata.remove(source.toAbsolutePath().normalize());
    }

    private Metadata metadata(Path source) throws IOException {
        var normalized = source.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".isyntax")) {
            throw new IOException("Not an iSyntax source");
        }
        if (!available()) throw new IOException("libisyntax runtime is unavailable at " + runtimeRoot);
        try {
            return metadata.computeIfAbsent(normalized, path -> {
                try {
                    return parseMetadata(new String(run(List.of("metadata", path.toString()),
                            MAX_METADATA_BYTES), StandardCharsets.UTF_8));
                } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
        } catch (java.io.UncheckedIOException error) { throw error.getCause(); }
    }

    private byte[] render(Path source, String format, int x, int y, int width, int height,
            int outputWidth, int outputHeight, int maximumBytes) throws IOException {
        if (x < 0 || y < 0 || width < 1 || height < 1 || outputWidth < 1 || outputHeight < 1
                || (long) outputWidth * outputHeight > MAX_REGION_PIXELS) {
            throw new IllegalArgumentException("iSyntax render region is invalid");
        }
        return run(List.of(format, source.toAbsolutePath().normalize().toString(),
                Integer.toString(x), Integer.toString(y), Integer.toString(width),
                Integer.toString(height), Integer.toString(outputWidth), Integer.toString(outputHeight)),
                maximumBytes);
    }

    private byte[] run(List<String> arguments, int maximumBytes) throws IOException {
        var command = new ArrayList<String>();
        command.add(python.toString()); command.add("-I"); command.add(bridge.toString());
        command.add(runtimeRoot.toString()); command.addAll(arguments);
        var process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.PIPE).start();
        process.getOutputStream().close();
        var stdout = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream(), maximumBytes));
        var stderr = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream(), MAX_ERROR_BYTES));
        try {
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("libisyntax child process timed out");
            }
            var output = stdout.join();
            var error = stderr.join();
            if (process.exitValue() != 0) {
                throw new IOException("libisyntax child process failed: "
                        + new String(error, StandardCharsets.UTF_8).trim());
            }
            return output;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("libisyntax read was cancelled", error);
        } catch (java.util.concurrent.CompletionException error) {
            if (error.getCause() instanceof java.io.UncheckedIOException unchecked) throw unchecked.getCause();
            throw error;
        }
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) {
        try {
            var output = new ByteArrayOutputStream(Math.min(maximumBytes, 65_536));
            var buffer = new byte[8192];
            var total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total = Math.addExact(total, count);
                if (total > maximumBytes) throw new IOException("libisyntax child output exceeded its bound");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    static Metadata parseMetadata(String json) throws IOException {
        var node = new ObjectMapper().readTree(json);
        var dimensions = new ArrayList<LevelDimensions>();
        for (var item : node.required("levelDimensions")) {
            dimensions.add(new LevelDimensions(item.required(0).asInt(), item.required(1).asInt()));
        }
        return new Metadata(node.required("width").asInt(), node.required("height").asInt(),
                node.required("levels").asInt(), node.required("mppX").asDouble(),
                node.required("mppY").asDouble(), List.copyOf(dimensions));
    }

    private String runtimeFingerprint() throws IOException {
        var digest = sha256Digest();
        digest.update(Files.readAllBytes(bridge));
        try (var files = Files.list(runtimeRoot.resolve("isyntax"))) {
            for (var path : files.filter(item -> item.getFileName().toString().startsWith("_pyisyntax"))
                    .sorted().toList()) digest.update(Files.readAllBytes(path));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private ImportProbeException unavailable(Path source) {
        return new ImportProbeException(new ImportDiagnostic(ImportDiagnostic.Code.CODEC_UNAVAILABLE,
                "Free libisyntax runtime is not installed at " + runtimeRoot, List.of(source)));
    }

    private static ReaderDescriptor descriptor(int levels) {
        return new ReaderDescriptor("LIBISYNTAX", "libisyntax", "Philips iSyntax",
                List.of("isyntax"), false, levels > 1, false, true);
    }

    private static void validateRegion(Metadata info, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 1 || height < 1
                || (long) x + width > info.width || (long) y + height > info.height) {
            throw new IllegalArgumentException("RGB region is outside the iSyntax slide");
        }
    }

    private static void requireSeries(int index) {
        if (index != 0) throw new IllegalArgumentException("iSyntax has one WSI series");
    }

    private static String configured(String property, String environment) {
        var value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void extractBridge(Path target) {
        try (var input = LibISyntaxEngine.class.getResourceAsStream("/reader-bridges/isyntax_bridge.py")) {
            if (input == null) return;
            Files.createDirectories(target.getParent());
            var partial = target.resolveSibling(target.getFileName() + ".partial");
            Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) { }
    }

    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    record LevelDimensions(int width, int height) { }
    record Metadata(int width, int height, int levels, double mppX, double mppY,
                    List<LevelDimensions> levelDimensions) { }
}
