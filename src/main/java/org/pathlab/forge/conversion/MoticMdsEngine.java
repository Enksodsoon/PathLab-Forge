package org.pathlab.forge.conversion;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.pathlab.forge.reader.DatasetProbe;
import org.pathlab.forge.reader.ReaderDescriptor;

/** Bounded reader for Motic MDS compound documents containing JPEG tile pyramids. */
public final class MoticMdsEngine implements ConversionEngine {
    private static final int TILE_SIZE = 512;
    private static final long MAX_REGION_PIXELS = 4_194_304L;
    private final Map<Path, MdsSlide> slides = new ConcurrentHashMap<>();

    @Override public boolean available() { return true; }

    @Override public String runtimeDescription() { return "PathLab Motic MDS reader"; }

    @Override
    public DatasetProbe.Result probe(Path source) throws IOException {
        var slide = slide(source);
        return new DatasetProbe.Result(
                new ReaderDescriptor("PATHLAB_MDS", "motic-mds-v1", "Motic MDS",
                        List.of("mds"), false, slide.levels.size() > 1, false, true),
                "Motic MDS", List.of(source.toAbsolutePath().normalize()),
                fingerprint("pathlab-motic-mds-v1"));
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    @Override
    public List<SeriesInfo> inspect(Path source) throws IOException {
        var slide = slide(source);
        return List.of(new SeriesInfo(0, "Motic digital slide", slide.width, slide.height,
                3, 1, 1, "uint8", 0, 0, "", slide.levels.size()));
    }

    @Override
    public void convert(Path source, int seriesIndex, Path output) throws IOException {
        throw new IOException("Motic MDS conversion requires the bounded region pipeline");
    }

    @Override public boolean supportsDirectTiles() { return true; }

    @Override
    public DirectTileSource directTileSource(Path source, int seriesIndex) throws IOException {
        requireSeries(seriesIndex);
        var slide = slide(source);
        return new DirectTileSource(slide.width, slide.height, TILE_SIZE);
    }

    @Override
    public byte[] readDirectTile(Path source, int seriesIndex, int level, int tileX, int tileY)
            throws IOException {
        requireSeries(seriesIndex);
        if (level < 0 || tileX < 0 || tileY < 0) {
            throw new IllegalArgumentException("Direct tile coordinates are invalid");
        }
        var slide = slide(source);
        var tileSource = new DirectTileSource(slide.width, slide.height, TILE_SIZE);
        if (level > tileSource.maximumLevel()) {
            throw new IllegalArgumentException("Direct tile level is invalid");
        }
        var downsample = Math.scalb(1.0, tileSource.maximumLevel() - level);
        var levelWidth = Math.max(1, (int) Math.ceil(slide.width / downsample));
        var levelHeight = Math.max(1, (int) Math.ceil(slide.height / downsample));
        var outputX = Math.multiplyExact(tileX, TILE_SIZE);
        var outputY = Math.multiplyExact(tileY, TILE_SIZE);
        if (outputX >= levelWidth || outputY >= levelHeight) {
            throw new IllegalArgumentException("Direct tile is outside the image");
        }
        var outputWidth = Math.min(TILE_SIZE, levelWidth - outputX);
        var outputHeight = Math.min(TILE_SIZE, levelHeight - outputY);
        var image = slide.readRegion(
                (int) Math.floor(outputX * downsample),
                (int) Math.floor(outputY * downsample),
                Math.max(1, (int) Math.ceil(outputWidth * downsample)),
                Math.max(1, (int) Math.ceil(outputHeight * downsample)),
                outputWidth, outputHeight);
        return encodeJpeg(image, 0.85f);
    }

    @Override
    public byte[] seriesThumbnail(Path source, int seriesIndex, int maxDimension)
            throws IOException {
        requireSeries(seriesIndex);
        if (maxDimension < 96 || maxDimension > 1024) {
            throw new IllegalArgumentException("Thumbnail bound is invalid");
        }
        var slide = slide(source);
        var scale = Math.min(1.0, (double) maxDimension / Math.max(slide.width, slide.height));
        var width = Math.max(1, (int) Math.round(slide.width * scale));
        var height = Math.max(1, (int) Math.round(slide.height * scale));
        return encodeJpeg(slide.readRegion(0, 0, slide.width, slide.height, width, height), 0.88f);
    }

    @Override
    public RgbRegion readRgbRegion(
            Path source, int seriesIndex, int x, int y, int width, int height) throws IOException {
        requireSeries(seriesIndex);
        var slide = slide(source);
        validateRegion(slide, x, y, width, height);
        var image = slide.readRegion(x, y, width, height, width, height);
        var rgb = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 3)];
        var offset = 0;
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var pixel = image.getRGB(column, row);
                rgb[offset++] = (byte) (pixel >>> 16);
                rgb[offset++] = (byte) (pixel >>> 8);
                rgb[offset++] = (byte) pixel;
            }
        }
        return new RgbRegion(x, y, width, height, rgb);
    }

    @Override public boolean supportsParallelRegions() { return true; }

    @Override
    public List<Path> convertRegions(
            ConversionRequest request, Path outputDirectory, int workers,
            BiConsumer<Integer, Integer> progress) throws IOException {
        requireSeries(request.seriesIndex());
        Files.createDirectories(outputDirectory);
        var outputStripeHeight = Math.max(1,
                (int) Math.min(request.outputHeight(), MAX_REGION_PIXELS / request.outputWidth()));
        var count = (request.outputHeight() + outputStripeHeight - 1) / outputStripeHeight;
        var outputs = new ArrayList<Path>(count);
        var slide = slide(request.source());
        for (var index = 0; index < count; index++) {
            var outputY = index * outputStripeHeight;
            var outputHeight = Math.min(outputStripeHeight, request.outputHeight() - outputY);
            var sourceTop = request.cropY()
                    + (int) Math.floor((double) outputY * request.cropHeight() / request.outputHeight());
            var sourceBottom = request.cropY()
                    + (int) Math.ceil((double) (outputY + outputHeight)
                            * request.cropHeight() / request.outputHeight());
            var image = slide.readRegion(request.cropX(), sourceTop, request.cropWidth(),
                    Math.max(1, sourceBottom - sourceTop), request.outputWidth(), outputHeight);
            var output = outputDirectory.resolve("mds-%05d.png".formatted(index));
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IOException("PNG writer is unavailable for Motic MDS rendering");
            }
            outputs.add(output);
            progress.accept(index + 1, count);
        }
        return List.copyOf(outputs);
    }

    @Override
    public void closeDirectSource(Path source) throws IOException {
        var removed = slides.remove(source.toAbsolutePath().normalize());
        if (removed != null) removed.close();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (var slide : slides.values()) {
            try { slide.close(); } catch (IOException error) { failure = error; }
        }
        slides.clear();
        if (failure != null) throw failure;
    }

    private MdsSlide slide(Path source) throws IOException {
        var normalized = source.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mds")) {
            throw new IOException("Not a Motic MDS source");
        }
        try {
            return slides.computeIfAbsent(normalized, path -> {
                try { return MdsSlide.open(path); }
                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
        } catch (java.io.UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private static void requireSeries(int seriesIndex) {
        if (seriesIndex != 0) throw new IllegalArgumentException("Motic MDS has one image series");
    }

    private static void validateRegion(MdsSlide slide, int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 1 || height < 1
                || (long) x + width > slide.width || (long) y + height > slide.height) {
            throw new IllegalArgumentException("RGB region is outside the Motic slide");
        }
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) return source;
        var output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return output;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG writer is unavailable");
        var writer = writers.next();
        try (var output = new ByteArrayOutputStream();
                var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private record Level(String name, double scale, int columns, int rows) {}

    private static final class MdsSlide implements AutoCloseable {
        private final POIFSFileSystem filesystem;
        private final DirectoryEntry dsi;
        private final List<Level> levels;
        private final int width;
        private final int height;

        private MdsSlide(POIFSFileSystem filesystem, DirectoryEntry dsi, List<Level> levels) {
            this.filesystem = filesystem;
            this.dsi = dsi;
            this.levels = levels;
            var full = levels.stream().max(Comparator.comparingDouble(Level::scale)).orElseThrow();
            width = Math.multiplyExact(full.columns, TILE_SIZE);
            height = Math.multiplyExact(full.rows, TILE_SIZE);
        }

        static MdsSlide open(Path path) throws IOException {
            var filesystem = new POIFSFileSystem(path.toFile(), true);
            try {
                var dsi = (DirectoryEntry) filesystem.getRoot().getEntry("DSI0");
                var levels = new ArrayList<Level>();
                for (var iterator = dsi.getEntries(); iterator.hasNext();) {
                    var entry = iterator.next();
                    if (!(entry instanceof DirectoryEntry directory)) continue;
                    double scale;
                    try { scale = Double.parseDouble(entry.getName()); }
                    catch (NumberFormatException ignored) { continue; }
                    var maximumX = -1;
                    var maximumY = -1;
                    for (var tileIterator = directory.getEntries(); tileIterator.hasNext();) {
                        var tile = tileIterator.next();
                        var parts = tile.getName().split("_");
                        if (!(tile instanceof DocumentEntry) || parts.length != 2) continue;
                        try {
                            maximumX = Math.max(maximumX, Integer.parseInt(parts[0]));
                            maximumY = Math.max(maximumY, Integer.parseInt(parts[1]));
                        } catch (NumberFormatException ignored) { }
                    }
                    if (maximumX >= 0 && maximumY >= 0) {
                        levels.add(new Level(entry.getName(), scale, maximumX + 1, maximumY + 1));
                    }
                }
                if (levels.isEmpty()) throw new IOException("Motic MDS contains no JPEG pyramid");
                levels.sort(Comparator.comparingDouble(Level::scale));
                return new MdsSlide(filesystem, dsi, List.copyOf(levels));
            } catch (IOException | RuntimeException error) {
                filesystem.close();
                throw error;
            }
        }

        synchronized BufferedImage readRegion(
                int fullX, int fullY, int fullWidth, int fullHeight,
                int outputWidth, int outputHeight) throws IOException {
            if (fullX < 0 || fullY < 0 || fullWidth < 1 || fullHeight < 1
                    || outputWidth < 1 || outputHeight < 1) {
                throw new IllegalArgumentException("Motic read region is invalid");
            }
            var requestedScale = Math.min((double) outputWidth / fullWidth,
                    (double) outputHeight / fullHeight);
            var level = levels.stream().filter(candidate -> candidate.scale >= requestedScale - 1e-9)
                    .min(Comparator.comparingDouble(Level::scale))
                    .orElse(levels.get(levels.size() - 1));
            var sourceX = (int) Math.floor(fullX * level.scale);
            var sourceY = (int) Math.floor(fullY * level.scale);
            var sourceRight = (int) Math.ceil((fullX + (long) fullWidth) * level.scale);
            var sourceBottom = (int) Math.ceil((fullY + (long) fullHeight) * level.scale);
            var sourceWidth = Math.max(1, sourceRight - sourceX);
            var sourceHeight = Math.max(1, sourceBottom - sourceY);
            var composed = new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB);
            var graphics = composed.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, sourceWidth, sourceHeight);
            var directory = (DirectoryEntry) dsi.getEntry(level.name);
            var firstColumn = sourceX / TILE_SIZE;
            var lastColumn = (sourceRight - 1) / TILE_SIZE;
            var firstRow = sourceY / TILE_SIZE;
            var lastRow = (sourceBottom - 1) / TILE_SIZE;
            for (var column = firstColumn; column <= lastColumn; column++) {
                for (var row = firstRow; row <= lastRow; row++) {
                    var name = "%04d_%04d".formatted(column, row);
                    if (!directory.hasEntry(name)) continue;
                    var document = (DocumentEntry) directory.getEntry(name);
                    byte[] bytes;
                    try (var input = new DocumentInputStream(document)) {
                        bytes = input.readAllBytes();
                    }
                    var tile = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (tile == null) throw new IOException("Invalid Motic JPEG tile " + name);
                    graphics.drawImage(tile, column * TILE_SIZE - sourceX,
                            row * TILE_SIZE - sourceY, null);
                }
            }
            graphics.dispose();
            return resize(composed, outputWidth, outputHeight);
        }

        @Override public synchronized void close() throws IOException { filesystem.close(); }
    }
}
