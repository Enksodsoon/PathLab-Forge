package org.pathlab.forge.packageformat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PreparedPackageBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsDeterministicCanonicalPackage() throws Exception {
        var derivative = Files.createDirectories(temporaryDirectory.resolve("derivative"));
        Files.writeString(
                derivative.resolve("slide.dzi"),
                """
                <Image xmlns="http://schemas.microsoft.com/deepzoom/2008"
                  Format="jpg" Overlap="1" TileSize="512"><Size Height="1" Width="1"/></Image>
                """);
        var level = Files.createDirectories(derivative.resolve("slide_files").resolve("0"));
        writeJpeg(level.resolve("0_0.jpg"));
        writeJpeg(derivative.resolve("thumbnail.jpg"));
        var first = temporaryDirectory.resolve("first.plslide");
        var second = temporaryDirectory.resolve("second.plslide");

        var metadata = new PackageMetadata(
                "artifact-1",
                "configuration-1",
                "source-fingerprint",
                2,
                10,
                20,
                30,
                40,
                1.5,
                0.25,
                0.25,
                "µm",
                "test");
        var firstInfo = PreparedPackageBuilder.build(derivative, 1, 1, metadata, first);
        var secondInfo = PreparedPackageBuilder.build(derivative, 1, 1, metadata, second);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertEquals(firstInfo.sha256(), secondInfo.sha256());
        assertEquals(3, firstInfo.derivativeFileCount());
        var listing = tarEntry(first, "manifest.json");
        assertTrue(listing.contains("\"schema\":\"pathlab-prepared-slide/v2\""));
        assertTrue(listing.contains("\"sourceFingerprint\":\"source-fingerprint\""));
        assertTrue(listing.contains("\"scale\":0.6666666666666666"));
        assertEquals(64, tarEntry(first, "manifest.sha256").length());
    }

    private static String tarEntry(Path archive, String expected) throws Exception {
        try (var input = Files.newInputStream(archive)) {
            while (true) {
                var header = input.readNBytes(512);
                if (header.length < 512 || header[0] == 0) {
                    throw new IllegalArgumentException("Tar entry was not found: " + expected);
                }
                var nameEnd = 0;
                while (nameEnd < 100 && header[nameEnd] != 0) {
                    nameEnd++;
                }
                var name = new String(header, 0, nameEnd, java.nio.charset.StandardCharsets.UTF_8);
                var sizeText = new String(
                                header, 124, 12, java.nio.charset.StandardCharsets.US_ASCII)
                        .replace("\0", "")
                        .trim();
                var size = Long.parseLong(sizeText, 8);
                var bytes = input.readNBytes(Math.toIntExact(size));
                var padding = (int) ((512 - size % 512) % 512);
                input.skipNBytes(padding);
                if (name.equals(expected)) {
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static void writeJpeg(Path path) throws Exception {
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", path.toFile());
    }
}
