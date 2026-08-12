package org.pathlab.forge.derivative;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

public final class DziValidator {
    private static final int TILE_SIZE = 512;
    private static final int OVERLAP = 1;
    private static final long MAX_FILES = 2_000_000;
    private static final Pattern TILE_NAME = Pattern.compile("(\\d+)_(\\d+)\\.jpg");

    private DziValidator() {}

    public static DerivativeInfo validate(Path root, int width, int height) throws IOException {
        return validate(root, width, height, (completed, total) -> {});
    }

    public static DerivativeInfo validate(
            Path root,
            int width,
            int height,
            java.util.function.BiConsumer<Long, Long> progress)
            throws IOException {
        var normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Derivative directory is missing");
        }
        var dzi = normalized.resolve("slide.dzi");
        var thumbnail = normalized.resolve("thumbnail.jpg");
        var tileRoot = normalized.resolve("slide_files");
        requireRegular(dzi);
        requireRegular(thumbnail);
        if (!Files.isDirectory(tileRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("DZI tile directory is missing");
        }
        var xml = Files.readString(dzi, StandardCharsets.UTF_8);
        if (!xml.contains("TileSize=\"512\"")
                || !xml.contains("Overlap=\"1\"")
                || !xml.contains("Format=\"jpg\"")
                || !xml.contains("Width=\"" + width + "\"")
                || !xml.contains("Height=\"" + height + "\"")) {
            throw new IOException("DZI descriptor does not match the Viewer contract");
        }

        var maxLevel = ceilLog2(Math.max(width, height));
        var expectedTiles = expectedTileCount(width, height);
        var totalValidationUnits = Math.addExact(Math.multiplyExact(expectedTiles, 2), 2);
        progress.accept(0L, totalValidationUnits);
        var dimensions = new HashMap<String, Dimensions>();
        long tileCount = 0;
        for (var level = 0; level <= maxLevel; level++) {
            var directory = tileRoot.resolve(Integer.toString(level));
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("DZI level is missing: " + level);
            }
            var divisor = 1L << Math.min(62, maxLevel - level);
            var levelWidth = Math.max(1, ceilDiv(width, divisor));
            var levelHeight = Math.max(1, ceilDiv(height, divisor));
            var columns = ceilDiv(levelWidth, TILE_SIZE);
            var rows = ceilDiv(levelHeight, TILE_SIZE);
            for (var row = 0; row < rows; row++) {
                for (var column = 0; column < columns; column++) {
                    var tile = directory.resolve(column + "_" + row + ".jpg");
                    requireRegular(tile);
                    if (sampleTile(column, row, columns, rows)) {
                        var image = ImageIO.read(tile.toFile());
                        if (image == null
                                || image.getWidth()
                                        != expectedTileDimension(levelWidth, column, columns)
                                || image.getHeight()
                                        != expectedTileDimension(levelHeight, row, rows)) {
                            throw new IOException("DZI tile has invalid dimensions: "
                                    + level + "/" + column + "_" + row);
                        }
                    }
                    dimensions.put(
                            "slide_files/" + level + "/" + column + "_" + row + ".jpg",
                            new Dimensions(
                                    expectedTileDimension(levelWidth, column, columns),
                                    expectedTileDimension(levelHeight, row, rows)));
                    tileCount++;
                    if (tileCount % 128 == 0 || tileCount == expectedTiles) {
                        progress.accept(tileCount, totalValidationUnits);
                    }
                    if (tileCount > MAX_FILES) {
                        throw new IOException("DZI tile count exceeds the safety limit");
                    }
                }
            }
        }

        long fileCount = 0;
        long bytes = 0;
        var ledger = new ArrayList<FileLedgerEntry>();
        var thumbnailDimensions = verifyPreviewContent(thumbnail, width, height);
        dimensions.put("thumbnail.jpg", thumbnailDimensions);
        List<Path> files;
        try (var paths = Files.walk(normalized)) {
            files = paths.filter(path -> !path.equals(normalized))
                    .filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        }
        for (var path : files) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                throw new IOException("Derivative contains a link or special file");
            }
            var relative = normalized.relativize(path).toString().replace('\\', '/');
            if (!relative.equals("slide.dzi")
                    && !relative.equals("thumbnail.jpg")
                    && !relative.matches("slide_files/\\d+/\\d+_\\d+\\.jpg")) {
                throw new IOException("Unexpected derivative file: " + relative);
            }
        }
        var validations = validateFiles(files);
        var digest = sha256Digest();
        for (var index = 0; index < files.size(); index++) {
            var path = files.get(index);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                throw new IOException("Derivative contains a link or special file");
            }
            var relative = normalized.relativize(path).toString().replace('\\', '/');
            fileCount++;
            var validation = validations.get(index);
            var size = validation.bytes();
            bytes = Math.addExact(bytes, size);
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(validation.sha256().getBytes(StandardCharsets.US_ASCII));
            var jpeg = relative.endsWith(".jpg");
            var imageDimensions = dimensions.getOrDefault(relative, Dimensions.NONE);
            ledger.add(new FileLedgerEntry(
                    relative,
                    size,
                    validation.sha256(),
                    jpeg,
                    jpeg,
                    imageDimensions.width(),
                    imageDimensions.height()));
            var completed = Math.addExact(tileCount, fileCount);
            if (fileCount % 128 == 0 || fileCount == tileCount + 2) {
                progress.accept(completed, totalValidationUnits);
            }
        }
        if (fileCount != tileCount + 2) {
            throw new IOException("Derivative has missing or duplicate files");
        }
        return new DerivativeInfo(
                normalized,
                bytes,
                Math.toIntExact(fileCount),
                Math.toIntExact(tileCount),
                HexFormat.of().formatHex(digest.digest()),
                ledger);
    }

    public static long expectedTileCount(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("DZI geometry is invalid");
        }
        var maximumLevel = ceilLog2(Math.max(width, height));
        long count = 0;
        for (var level = 0; level <= maximumLevel; level++) {
            var divisor = 1L << Math.min(62, maximumLevel - level);
            var levelWidth = Math.max(1, ceilDiv(width, divisor));
            var levelHeight = Math.max(1, ceilDiv(height, divisor));
            count = Math.addExact(
                    count,
                    Math.multiplyExact(
                            (long) ceilDiv(levelWidth, TILE_SIZE),
                            ceilDiv(levelHeight, TILE_SIZE)));
        }
        return count;
    }

    private static List<FileValidation> validateFiles(List<Path> paths) throws IOException {
        var workers = Math.max(
                1,
                Math.min(
                        10,
                        org.pathlab.forge.runtime.RuntimeProfile.system()
                                .maxConversionWorkers()));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(workers, Math.max(1, paths.size())),
                runnable -> {
                    var thread = new Thread(runnable, "pathlab-dzi-validator");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            var futures = new ArrayList<java.util.concurrent.Future<FileValidation>>();
            for (var path : paths) {
                futures.add(executor.submit(
                        () -> hashAndValidate(path, path.toString().endsWith(".jpg"))));
            }
            var validated = new ArrayList<FileValidation>(paths.size());
            for (var future : futures) {
                try {
                    validated.add(future.get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("DZI validation was interrupted", error);
                } catch (java.util.concurrent.ExecutionException error) {
                    var cause = error.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException("Parallel DZI validation failed", cause);
                }
            }
            return List.copyOf(validated);
        } finally {
            executor.shutdownNow();
        }
    }

    private static FileValidation hashAndValidate(Path path, boolean jpeg) throws IOException {
        var fileDigest = sha256Digest();
        var size = Files.size(path);
        if (jpeg && size < 4) {
            throw new IOException("JPEG is too short");
        }
        var first = new byte[2];
        var firstCount = 0;
        var penultimate = -1;
        var last = -1;
        long bytes = 0;
        try (InputStream input = Files.newInputStream(path)) {
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                fileDigest.update(buffer, 0, read);
                bytes = Math.addExact(bytes, read);
                for (var index = 0; index < read && firstCount < first.length; index++) {
                    first[firstCount++] = buffer[index];
                }
                if (read == 1) {
                    penultimate = last;
                    last = buffer[0] & 0xff;
                } else if (read >= 2) {
                    penultimate = buffer[read - 2] & 0xff;
                    last = buffer[read - 1] & 0xff;
                }
            }
        }
        if (jpeg
                && (firstCount < 2
                        || (first[0] & 0xff) != 0xff
                        || (first[1] & 0xff) != 0xd8
                        || penultimate != 0xff
                        || last != 0xd9)) {
            throw new IOException("Invalid JPEG signature");
        }
        if (bytes != size) {
            throw new IOException("Derivative file changed during validation: " + path);
        }
        return new FileValidation(size, HexFormat.of().formatHex(fileDigest.digest()));
    }

    private static boolean sampleTile(
            int column, int row, int columns, int rows) {
        return (column == 0 && row == 0)
                || (column == columns - 1 && row == 0)
                || (column == 0 && row == rows - 1)
                || (column == columns - 1 && row == rows - 1)
                || (column == columns / 2 && row == rows / 2);
    }

    private static int expectedTileDimension(long levelSize, int index, int count) {
        var start = (long) index * TILE_SIZE;
        var core = (int) Math.min(TILE_SIZE, levelSize - start);
        var before = index == 0 ? 0 : OVERLAP;
        var after = index == count - 1 ? 0 : OVERLAP;
        return core + before + after;
    }

    private static int ceilDiv(long value, long divisor) {
        return Math.toIntExact((value + divisor - 1) / divisor);
    }

    private static int ceilLog2(int value) {
        var result = 0;
        var current = 1L;
        while (current < value) {
            current <<= 1;
            result++;
        }
        return result;
    }

    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new IOException("Required derivative file is missing");
        }
    }

    private static Dimensions verifyPreviewContent(Path thumbnail, int width, int height)
            throws IOException {
        var image = ImageIO.read(thumbnail.toFile());
        if (image == null) {
            throw new IOException("Derivative thumbnail could not be decoded");
        }
        if ((long) width * height < (long) TILE_SIZE * TILE_SIZE) {
            return new Dimensions(image.getWidth(), image.getHeight());
        }
        var minimum = 255;
        var maximum = 0;
        double sum = 0;
        double squared = 0;
        var pixels = (long) image.getWidth() * image.getHeight();
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                var rgb = image.getRGB(x, y);
                var luminance = (int) Math.round(
                        0.2126 * ((rgb >>> 16) & 0xff)
                                + 0.7152 * ((rgb >>> 8) & 0xff)
                                + 0.0722 * (rgb & 0xff));
                minimum = Math.min(minimum, luminance);
                maximum = Math.max(maximum, luminance);
                sum += luminance;
                squared += (double) luminance * luminance;
            }
        }
        var mean = sum / pixels;
        var variance = Math.max(0, squared / pixels - mean * mean);
        if (maximum - minimum < 12 && variance < 4) {
            throw new IOException("Derivative preview is blank or near-blank");
        }
        return new Dimensions(image.getWidth(), image.getHeight());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record Dimensions(int width, int height) {
        private static final Dimensions NONE = new Dimensions(0, 0);
    }

    private record FileValidation(long bytes, String sha256) {}
}
