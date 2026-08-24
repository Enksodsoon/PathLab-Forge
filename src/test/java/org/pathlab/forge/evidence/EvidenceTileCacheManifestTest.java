package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceTileCacheManifestTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void validatesEveryCoordinatePixelAndProvenanceChecksum() throws Exception {
        var fixture = fixture();
        var cache = EvidenceTileCacheManifest.load(fixture.manifest(), sha256(fixture.manifest()));

        assertEquals(512, cache.tilePixels());
        assertEquals(1, cache.tiles().size());
        cache.requireSource(sha256(fixture.source()), "revision-1", 1024, 768, 512);
    }

    @Test
    void rejectsTamperedTileAndStaleRevision() throws Exception {
        var tampered = fixture();
        Files.writeString(tampered.tile(), "tampered");
        assertThrows(IllegalArgumentException.class, () ->
                EvidenceTileCacheManifest.load(tampered.manifest(), sha256(tampered.manifest())));

        var stale = fixture();
        var loaded = EvidenceTileCacheManifest.load(stale.manifest(), sha256(stale.manifest()));
        assertThrows(IllegalArgumentException.class, () ->
                loaded.requireSource(sha256(stale.source()), "revision-2", 1024, 768, 512));
    }

    @Test
    void rejectsPathTraversalEvenWhenOutsideTileChecksumMatches() throws Exception {
        var fixture = fixture();
        var outside = temporaryDirectory.resolve("outside.png");
        Files.copy(fixture.tile(), outside);
        var root = JSON.readTree(fixture.manifest().toFile());
        var tile = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("tiles").get(0);
        tile.put("path", "../outside.png");
        tile.put("sha256", sha256(outside));
        JSON.writeValue(fixture.manifest().toFile(), root);

        assertThrows(IllegalArgumentException.class, () ->
                EvidenceTileCacheManifest.load(fixture.manifest(), sha256(fixture.manifest())));
    }

    private Fixture fixture() throws Exception {
        var root = temporaryDirectory.resolve(java.util.UUID.randomUUID().toString());
        Files.createDirectories(root.resolve("tiles"));
        var source = root.resolve("source.png");
        var tile = root.resolve("tiles/tile-1.png");
        writeImage(source, 1024, 768);
        writeImage(tile, 512, 512);
        var sample = root.resolve("sample.json");
        Files.writeString(sample, "{\"schema\":\"pathlab.evidence-sample/1\","
                + "\"source\":\"fixture\",\"patientGroup\":\"patient-1\","
                + "\"slideId\":\"slide-1\",\"sha256\":\"" + sha256(source) + "\","
                + "\"license\":\"private fixture\",\"taskLabel\":\"breast-benign\","
                + "\"permittedUse\":\"private-research\",\"bytes\":" + Files.size(source) + ","
                + "\"grandfatheredReadOnly\":false}");
        var manifest = root.resolve("tile-cache.json");
        Files.writeString(manifest, "{\"schema\":\"pathlab.tile-cache/1\",\"source\":{"
                + "\"sha256\":\"" + sha256(source) + "\",\"slideRevision\":\"revision-1\","
                + "\"width\":1024,\"height\":768,\"sampleManifest\":\"sample.json\","
                + "\"sampleManifestSha256\":\"" + sha256(sample) + "\"},"
                + "\"tilePixels\":512,\"encoding\":\"png\","
                + "\"preprocessingInput\":\"rgb-srgb-uint8\",\"tiles\":[{"
                + "\"id\":\"tile-1\",\"path\":\"tiles/tile-1.png\","
                + "\"sha256\":\"" + sha256(tile) + "\",\"x\":0,\"y\":0,"
                + "\"width\":512,\"height\":512}]}");
        return new Fixture(source, tile, manifest);
    }

    private static void writeImage(Path path, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(174, 91, 145));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    private record Fixture(Path source, Path tile, Path manifest) {}
}
