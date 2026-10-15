package org.pathlab.forge.conversion;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import org.pathlab.forge.reader.DatasetProbe;
import org.pathlab.forge.reader.ImportDiagnostic;
import org.pathlab.forge.reader.ImportProbeException;
import org.pathlab.forge.reader.ReaderDescriptor;

/** Optional contained adapter for the Sqray SDPC native reader. */
public final class SdpcEngine implements ConversionEngine {
    private static final int TILE_SIZE = 512;
    private static final long MAX_REGION_PIXELS = 4_194_304L;
    private final Path runtimeRoot;
    private final SdpcApi api;
    private final Map<Path, Slide> slides = new ConcurrentHashMap<>();

    public static SdpcEngine discover(Path dataRoot) {
        if (Boolean.getBoolean("pathlab.forge.runtime.requireProduction")) {
            return new SdpcEngine(org.pathlab.forge.runtime.ReaderRuntimeLocator.componentRoot(dataRoot, "sdpc")
                    .orElse(null));
        }
        var configured = System.getProperty("pathlab.forge.sdpcRuntime");
        if (configured == null || configured.isBlank()) configured = System.getenv("PATHLAB_FORGE_SDPC_RUNTIME");
        var root = configured == null || configured.isBlank()
                ? org.pathlab.forge.runtime.ReaderRuntimeLocator.componentRoot(dataRoot, "sdpc")
                        .orElse(dataRoot.resolve("runtime").resolve("sdpc"))
                : Path.of(configured);
        return new SdpcEngine(root);
    }

    SdpcEngine(Path runtimeRoot) {
        this.runtimeRoot = runtimeRoot == null ? null : runtimeRoot.toAbsolutePath().normalize();
        var library = this.runtimeRoot == null ? null : this.runtimeRoot.resolve("DecodeSdpcDll.dll");
        SdpcApi loaded = null;
        if (library != null && Files.isRegularFile(library)) {
            try {
                NativeLibrary.addSearchPath("DecodeSdpcDll", this.runtimeRoot.toString());
                loaded = Native.load(library.toString(), SdpcApi.class,
                        Map.of(Library.OPTION_STRING_ENCODING, "GBK"));
            } catch (LinkageError | RuntimeException ignored) {
                loaded = null;
            }
        }
        api = loaded;
    }

    @Override public boolean available() { return api != null; }

    @Override public String runtimeDescription() {
        return available() ? "Sqray SDPC native reader at " + runtimeRoot
                : "Sqray SDPC native reader unavailable";
    }

    @Override
    public DatasetProbe.Result probe(Path source) throws IOException, ImportProbeException {
        if (!available()) throw unavailable(source);
        var slide = open(source);
        synchronized (slide) {
            return new DatasetProbe.Result(descriptor(slide.levels.size()), "SDPC",
                    List.of(source.toAbsolutePath().normalize()), "8fca6b3fc10dccab9348a1ee114d36d61f576a4c35cab7d433e8b7908678f3b3");
        }
    }

    @Override
    public List<SeriesInfo> inspect(Path source) throws IOException {
        var slide = open(source);
        synchronized (slide) {
            var full = slide.levels.get(0);
            return List.of(new SeriesInfo(0, "SDPC digital slide", full.width, full.height,
                    3, 1, 1, "uint8", 0, 0, "", slide.levels.size()));
        }
    }

    @Override public void convert(Path source, int seriesIndex, Path output) throws IOException {
        throw new IOException("SDPC conversion requires the bounded region pipeline");
    }

    @Override public boolean supportsDirectTiles() { return available(); }

    @Override public DirectTileSource directTileSource(Path source, int seriesIndex) throws IOException {
        requireSeries(seriesIndex);
        var info = inspect(source).get(0);
        return new DirectTileSource(info.width(), info.height(), TILE_SIZE);
    }

    @Override
    public byte[] readDirectTile(Path source, int seriesIndex, int level, int tileX, int tileY)
            throws IOException {
        requireSeries(seriesIndex);
        var slide = open(source);
        synchronized (slide) {
            var full = slide.levels.get(0);
            var dzi = new DirectTileSource(full.width, full.height, TILE_SIZE);
            if (level < 0 || level > dzi.maximumLevel() || tileX < 0 || tileY < 0) {
                throw new IllegalArgumentException("Direct tile coordinates are invalid");
            }
            var downsample = Math.scalb(1.0, dzi.maximumLevel() - level);
            var levelWidth = Math.max(1, (int) Math.ceil(full.width / downsample));
            var levelHeight = Math.max(1, (int) Math.ceil(full.height / downsample));
            var x = Math.multiplyExact(tileX, TILE_SIZE);
            var y = Math.multiplyExact(tileY, TILE_SIZE);
            if (x >= levelWidth || y >= levelHeight) throw new IllegalArgumentException("Tile is outside SDPC image");
            var width = Math.min(TILE_SIZE, levelWidth - x);
            var height = Math.min(TILE_SIZE, levelHeight - y);
            return encodeJpeg(readScaled(slide, (int) Math.floor(x * downsample),
                    (int) Math.floor(y * downsample), Math.max(1, (int) Math.ceil(width * downsample)),
                    Math.max(1, (int) Math.ceil(height * downsample)), width, height), 0.85f);
        }
    }

    @Override
    public byte[] seriesThumbnail(Path source, int seriesIndex, int maxDimension) throws IOException {
        requireSeries(seriesIndex);
        if (maxDimension < 96 || maxDimension > 1024) throw new IllegalArgumentException("Thumbnail bound is invalid");
        var slide = open(source);
        synchronized (slide) {
            var full = slide.levels.get(0);
            var scale = Math.min(1.0, (double) maxDimension / Math.max(full.width, full.height));
            var width = Math.max(1, (int) Math.round(full.width * scale));
            var height = Math.max(1, (int) Math.round(full.height * scale));
            return encodeJpeg(readScaled(slide, 0, 0, full.width, full.height, width, height), 0.88f);
        }
    }

    @Override
    public RgbRegion readRgbRegion(Path source, int seriesIndex, int x, int y, int width, int height)
            throws IOException {
        requireSeries(seriesIndex);
        var slide = open(source);
        synchronized (slide) {
            var full = slide.levels.get(0);
            validateRegion(full, x, y, width, height);
            var image = readScaled(slide, x, y, width, height, width, height);
            var rgb = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 3)];
            var offset = 0;
            for (var row = 0; row < height; row++) for (var column = 0; column < width; column++) {
                var pixel = image.getRGB(column, row);
                rgb[offset++] = (byte) (pixel >>> 16); rgb[offset++] = (byte) (pixel >>> 8); rgb[offset++] = (byte) pixel;
            }
            return new RgbRegion(x, y, width, height, rgb);
        }
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
        var slide = open(request.source());
        synchronized (slide) {
            for (var index = 0; index < count; index++) {
                var outputY = index * stripeHeight;
                var outputHeight = Math.min(stripeHeight, request.outputHeight() - outputY);
                var sourceTop = request.cropY() + (int) Math.floor((double) outputY * request.cropHeight() / request.outputHeight());
                var sourceBottom = request.cropY() + (int) Math.ceil((double) (outputY + outputHeight) * request.cropHeight() / request.outputHeight());
                var image = readScaled(slide, request.cropX(), sourceTop, request.cropWidth(),
                        Math.max(1, sourceBottom - sourceTop), request.outputWidth(), outputHeight);
                var output = outputDirectory.resolve("sdpc-%05d.png".formatted(index));
                if (!ImageIO.write(image, "png", output.toFile())) throw new IOException("PNG writer is unavailable");
                outputs.add(output); progress.accept(index + 1, count);
            }
        }
        return List.copyOf(outputs);
    }

    @Override public void closeDirectSource(Path source) {
        var removed = slides.remove(source.toAbsolutePath().normalize());
        if (removed != null) synchronized (removed) { api.SqCloseSdpc(removed.pointer); }
    }

    @Override public void close() {
        for (var slide : slides.values()) synchronized (slide) { api.SqCloseSdpc(slide.pointer); }
        slides.clear();
    }

    private BufferedImage readScaled(Slide slide, int fullX, int fullY, int fullWidth, int fullHeight,
            int outputWidth, int outputHeight) throws IOException {
        var requested = Math.min((double) outputWidth / fullWidth, (double) outputHeight / fullHeight);
        var chosen = slide.levels.get(0);
        for (var candidate : slide.levels) {
            if ((double) candidate.width / slide.levels.get(0).width >= requested) chosen = candidate;
        }
        var scaleX = (double) chosen.width / slide.levels.get(0).width;
        var scaleY = (double) chosen.height / slide.levels.get(0).height;
        var x = (int) Math.floor(fullX * scaleX); var y = (int) Math.floor(fullY * scaleY);
        var width = Math.max(1, (int) Math.ceil(fullWidth * scaleX));
        var height = Math.max(1, (int) Math.ceil(fullHeight * scaleY));
        var output = new PointerByReference();
        var result = api.SqGetRoiRgbOfSpecifyLayer(slide.pointer, output, width, height, x, y, chosen.index);
        var pointer = output.getValue();
        if (result != 0 || pointer == null) throw new IOException("SDPC native region decode failed: " + result);
        try {
            var bgr = pointer.getByteArray(0, Math.multiplyExact(Math.multiplyExact(width, height), 3));
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var offset = 0;
            for (var row = 0; row < height; row++) for (var column = 0; column < width; column++) {
                var blue = bgr[offset++] & 255; var green = bgr[offset++] & 255; var red = bgr[offset++] & 255;
                image.setRGB(column, row, (red << 16) | (green << 8) | blue);
            }
            if (width == outputWidth && height == outputHeight) return image;
            var resized = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
            var graphics = resized.createGraphics();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, outputWidth, outputHeight, null); graphics.dispose();
            return resized;
        } finally { api.Dispose(pointer); }
    }

    private Slide open(Path source) throws IOException {
        if (!available()) throw new IOException("SDPC codec unavailable at " + runtimeRoot);
        var normalized = source.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sdpc")) throw new IOException("Not an SDPC source");
        try {
            return slides.computeIfAbsent(normalized, path -> {
                try { return openUncached(path); }
                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
        } catch (java.io.UncheckedIOException error) { throw error.getCause(); }
    }

    private Slide openUncached(Path normalized) throws IOException {
        var pointer = api.SqOpenSdpc(normalized.toString());
        if (pointer == null) throw new IOException("SDPC native reader could not open source");
        try {
            var levels = new ArrayList<Level>();
            for (var index = 0; index < 32; index++) {
                var info = api.GetLayerInfo(pointer, index);
                if (info == null) break;
                var level = parseLevel(index, info.getString(0, "GBK"));
                if (level == null) break;
                levels.add(level);
            }
            if (levels.isEmpty()) throw new IOException("SDPC contains no readable pyramid levels");
            return new Slide(pointer, List.copyOf(levels));
        } catch (IOException | RuntimeException error) { api.SqCloseSdpc(pointer); throw error; }
    }

    static Level parseLevel(int index, String value) {
        if (value == null) return null;
        var fields = new java.util.HashMap<String, Integer>();
        for (var item : value.split("\\|")) {
            var parts = item.split("=", 2);
            if (parts.length == 2) try { fields.put(parts[0], Integer.parseInt(parts[1])); } catch (NumberFormatException ignored) { }
        }
        var width = fields.get("LayerWidth"); var height = fields.get("LayerHeight");
        if (width == null || height == null || width <= 0 || height <= 0) return null;
        return new Level(index, width - fields.getOrDefault("BoundWidth", 0),
                height - fields.getOrDefault("BoundHeight", 0));
    }

    private ImportProbeException unavailable(Path source) {
        return new ImportProbeException(new ImportDiagnostic(ImportDiagnostic.Code.CODEC_UNAVAILABLE,
                "SDPC native runtime is not installed at " + runtimeRoot, List.of(source)));
    }

    private static ReaderDescriptor descriptor(int levels) {
        return new ReaderDescriptor("SDPC_NATIVE", "sqray-sdpc", "SDPC", List.of("sdpc"),
                false, levels > 1, false, true);
    }

    private static void requireSeries(int index) { if (index != 0) throw new IllegalArgumentException("SDPC has one image series"); }
    private static void validateRegion(Level full, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 1 || height < 1 || (long) x + width > full.width || (long) y + height > full.height)
            throw new IllegalArgumentException("RGB region is outside the SDPC slide");
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG writer is unavailable");
        var writer = writers.next();
        try (var output = new ByteArrayOutputStream(); var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream); var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT); parameters.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters); return output.toByteArray();
        } finally { writer.dispose(); }
    }

    record Level(int index, int width, int height) {}
    private record Slide(Pointer pointer, List<Level> levels) {}

    interface SdpcApi extends Library {
        Pointer SqOpenSdpc(String path);
        void SqCloseSdpc(Pointer slide);
        Pointer GetLayerInfo(Pointer slide, int level);
        int SqGetRoiRgbOfSpecifyLayer(Pointer slide, PointerByReference output,
                int width, int height, int x, int y, int level);
        void Dispose(Pointer output);
    }
}
