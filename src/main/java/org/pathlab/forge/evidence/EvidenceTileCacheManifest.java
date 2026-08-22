package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Immutable, coordinate-bound RGB input tiles for one exact source revision. */
public record EvidenceTileCacheManifest(
        Path path,
        String sha256,
        String sourceSha256,
        String slideRevision,
        int sourceWidth,
        int sourceHeight,
        Path sampleManifestPath,
        String sampleManifestSha256,
        int tilePixels,
        String encoding,
        String preprocessingInput,
        EvidenceSampleManifest sample,
        List<Tile> tiles) {
    public static final String SCHEMA = "pathlab.tile-cache/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema", "source", "tilePixels", "encoding", "preprocessingInput", "tiles");
    private static final Set<String> SOURCE_FIELDS = Set.of(
            "sha256", "slideRevision", "width", "height", "sampleManifest", "sampleManifestSha256");
    private static final Set<String> TILE_FIELDS = Set.of(
            "id", "path", "sha256", "x", "y", "width", "height");

    public static EvidenceTileCacheManifest load(Path manifest, String expectedSha256) throws IOException {
        var normalized = manifest.toAbsolutePath().normalize();
        require(Files.isRegularFile(normalized), "Tile-cache manifest is unavailable");
        var bytes = Files.readAllBytes(normalized);
        var actualManifestSha = sha256(bytes);
        require(SHA.matcher(expectedSha256).matches() && expectedSha256.equals(actualManifestSha),
                "Tile-cache manifest checksum does not match");
        var root = JSON.readTree(bytes);
        require(root.isObject() && fields(root).equals(ROOT_FIELDS), "Tile-cache fields are invalid");
        require(SCHEMA.equals(text(root, "schema", 80)), "Tile-cache schema is unsupported");
        var source = root.path("source");
        require(source.isObject() && fields(source).equals(SOURCE_FIELDS), "Tile-cache source is invalid");
        var sourceSha = text(source, "sha256", 64);
        require(SHA.matcher(sourceSha).matches(), "Tile-cache source checksum is invalid");
        var revision = text(source, "slideRevision", 200);
        var sourceWidth = positiveInt(source, "width");
        var sourceHeight = positiveInt(source, "height");
        var tilePixels = positiveInt(root, "tilePixels");
        require(tilePixels == 512, "Evidence tile cache must contain 512-pixel tiles");
        var encoding = text(root, "encoding", 20);
        require("png".equals(encoding), "Evidence tile cache must use deterministic PNG tiles");
        var preprocessingInput = text(root, "preprocessingInput", 80);
        require("rgb-srgb-uint8".equals(preprocessingInput), "Evidence tile input encoding is unsupported");

        var rootDirectory = normalized.getParent().toRealPath();
        var samplePath = resolveEntry(rootDirectory, text(source, "sampleManifest", 500));
        var expectedSampleSha = text(source, "sampleManifestSha256", 64);
        require(SHA.matcher(expectedSampleSha).matches() && expectedSampleSha.equals(sha256(samplePath)),
                "Evidence sample manifest checksum does not match");
        var sample = EvidenceSampleManifest.load(samplePath);
        require(sourceSha.equals(sample.sha256()), "Tile-cache and sample source checksums differ");

        var tileNodes = root.path("tiles");
        require(tileNodes.isArray() && !tileNodes.isEmpty() && tileNodes.size() <= 512,
                "Evidence tile list is invalid");
        var ids = new HashSet<String>();
        var coordinates = new HashSet<String>();
        var tiles = new ArrayList<Tile>();
        for (var node : tileNodes) {
            require(node.isObject() && fields(node).equals(TILE_FIELDS), "Evidence tile fields are invalid");
            var id = text(node, "id", 120);
            require(ID.matcher(id).matches() && ids.add(id), "Evidence tile id is invalid or duplicated");
            var path = resolveEntry(rootDirectory, text(node, "path", 500));
            var expectedTileSha = text(node, "sha256", 64);
            require(SHA.matcher(expectedTileSha).matches() && expectedTileSha.equals(sha256(path)),
                    "Evidence tile checksum does not match");
            var x = nonnegativeInt(node, "x");
            var y = nonnegativeInt(node, "y");
            var width = positiveInt(node, "width");
            var height = positiveInt(node, "height");
            require(width == tilePixels && height == tilePixels
                            && (long) x + width <= sourceWidth && (long) y + height <= sourceHeight
                            && coordinates.add(x + ":" + y),
                    "Evidence tile coordinates are invalid or duplicated");
            BufferedImage image;
            try {
                image = ImageIO.read(path.toFile());
            } catch (javax.imageio.IIOException invalidImage) {
                throw new IllegalArgumentException("Evidence tile could not be decoded", invalidImage);
            }
            require(image != null && image.getWidth() == tilePixels && image.getHeight() == tilePixels
                            && image.getColorModel().getNumColorComponents() == 3,
                    "Evidence tile pixels are invalid");
            tiles.add(new Tile(id, path, expectedTileSha, x, y, width, height));
        }
        return new EvidenceTileCacheManifest(normalized, actualManifestSha, sourceSha, revision,
                sourceWidth, sourceHeight, samplePath, expectedSampleSha, tilePixels, encoding,
                preprocessingInput, sample, List.copyOf(tiles));
    }

    public void requireSource(String sha, String revision, int width, int height, int requiredTilePixels) {
        require(sourceSha256.equals(sha) && slideRevision.equals(revision)
                        && sourceWidth == width && sourceHeight == height && tilePixels == requiredTilePixels,
                "Tile cache is stale or belongs to another source revision");
    }

    private static Path resolveEntry(Path root, String relative) throws IOException {
        var candidate = Path.of(relative);
        require(!candidate.isAbsolute(), "Tile-cache entries must use relative paths");
        var resolved = root.resolve(candidate).normalize();
        require(resolved.startsWith(root) && Files.isRegularFile(resolved),
                "Tile-cache entry escaped its immutable root or is unavailable");
        var real = resolved.toRealPath();
        require(real.startsWith(root), "Tile-cache entry escaped its immutable root");
        return real;
    }

    private static Set<String> fields(JsonNode node) {
        var result = new HashSet<String>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static int positiveInt(JsonNode node, String field) {
        var value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0,
                "Evidence tile geometry is invalid");
        return value.intValue();
    }

    private static int nonnegativeInt(JsonNode node, String field) {
        var value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0,
                "Evidence tile coordinates are invalid");
        return value.intValue();
    }

    private static String text(JsonNode node, String field, int maximum) {
        var value = node.path(field);
        require(value.isTextual() && !value.textValue().isBlank() && value.textValue().length() <= maximum,
                "Evidence tile field is invalid: " + field);
        return value.textValue();
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Tile(String id, Path path, String sha256, int x, int y, int width, int height) {}
}
