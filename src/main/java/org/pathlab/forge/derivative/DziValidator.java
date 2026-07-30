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
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

public final class DziValidator {
    private static final int TILE_SIZE = 512;
    private static final int OVERLAP = 1;
    private static final long MAX_FILES = 2_000_000;
    private static final Pattern TILE_NAME = Pattern.compile("(\\d+)_(\\d+)\\.jpg");

    private DziValidator() {}

    public static DerivativeInfo validate(Path root, int width, int height) throws IOException {
        var normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Derivative directory is missing");
        }
        var dzi = normalized.resolve("slide.dzi");
        var thumbnail = normalized.resolve("thumbnail.jpg");
        var tileRoot = normalized.resolve("slide_files");
        requireRegular(dzi);
        requireJpeg(thumbnail);
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
                    requireJpeg(tile);
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
                    if (tileCount > MAX_FILES) {
                        throw new IOException("DZI tile count exceeds the safety limit");
                    }
                }
            }
        }

        long fileCount = 0;
        long bytes = 0;
        var digest = sha256Digest();
        var ledger = new ArrayList<FileLedgerEntry>();
        var thumbnailDimensions = verifyPreviewContent(thumbnail, width, height);
        dimensions.put("thumbnail.jpg", thumbnailDimensions);
        try (var paths = Files.walk(normalized)) {
            for (var path : paths.sorted().toList()) {
                if (path.equals(normalized) || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
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
                fileCount++;
                var size = Files.size(path);
                bytes = Math.addExact(bytes, size);
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                var fileHash = hash(path, digest);
                var jpeg = relative.endsWith(".jpg");
                var imageDimensions = dimensions.getOrDefault(relative, Dimensions.NONE);
                ledger.add(new FileLedgerEntry(
                        relative,
                        size,
                        fileHash,
                        jpeg,
                        jpeg,
                        imageDimensions.width(),
                        imageDimensions.height()));
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

    private static String hash(Path path, MessageDigest aggregate) throws IOException {
        var fileDigest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                aggregate.update(buffer, 0, read);
                fileDigest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(fileDigest.digest());
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

    private static void requireJpeg(Path path) throws IOException {
        requireRegular(path);
        if (Files.size(path) < 4) {
            throw new IOException("JPEG is too short");
        }
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read() != 0xff || input.read() != 0xd8) {
                throw new IOException("Invalid JPEG signature");
            }
        }
        try (var channel = Files.newByteChannel(path)) {
            var tail = java.nio.ByteBuffer.allocate(2);
            channel.position(Files.size(path) - 2);
            channel.read(tail);
            tail.flip();
            if ((tail.get() & 0xff) != 0xff || (tail.get() & 0xff) != 0xd9) {
                throw new IOException("JPEG end marker is missing");
            }
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
}
