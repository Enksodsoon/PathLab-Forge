package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DziValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesCompleteNonSparseDziAndThumbnail() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("slide.dzi"),
                """
                <Image xmlns="http://schemas.microsoft.com/deepzoom/2008"
                  Format="jpg" Overlap="1" TileSize="512">
                  <Size Height="1" Width="1"/>
                </Image>
                """);
        var level = Files.createDirectories(
                temporaryDirectory.resolve("slide_files").resolve("0"));
        writeJpeg(level.resolve("0_0.jpg"), 1, 1);
        writeJpeg(temporaryDirectory.resolve("thumbnail.jpg"), 1, 1);

        var result = DziValidator.validate(temporaryDirectory, 1, 1);

        assertEquals(1, result.tileCount());
        assertEquals(3, result.fileCount());
        assertEquals(3, result.ledger().size());
        var tile = result.ledger().stream()
                .filter(entry -> entry.path().equals("slide_files/0/0_0.jpg"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, tile.width());
        assertEquals(1, tile.height());
        assertEquals(64, tile.sha256().length());
    }

    @Test
    void predictsTheExactCompletePyramidTileCount() {
        assertEquals(21_402, DziValidator.expectedTileCount(78_785, 52_837));
        assertEquals(1, DziValidator.expectedTileCount(1, 1));
    }

    @Test
    void rejectsRedundantOrMissingDerivativeFiles() throws Exception {
        Files.writeString(temporaryDirectory.resolve("vips-properties.xml"), "redundant");

        assertThrows(
                java.io.IOException.class,
                () -> DziValidator.validate(temporaryDirectory, 1, 1));
    }

    @Test
    void rejectsNearBlankLargeDerivativeThatOnlyPassesStructuralChecks() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("slide.dzi"),
                """
                <Image xmlns="http://schemas.microsoft.com/deepzoom/2008"
                  Format="jpg" Overlap="1" TileSize="512">
                  <Size Height="512" Width="512"/>
                </Image>
                """);
        for (var level = 0; level <= 9; level++) {
            var directory = Files.createDirectories(
                    temporaryDirectory.resolve("slide_files").resolve(Integer.toString(level)));
            var size = Math.max(1, 1 << level);
            writeJpeg(directory.resolve("0_0.jpg"), size, size, 0xffffff);
        }
        writeJpeg(temporaryDirectory.resolve("thumbnail.jpg"), 512, 512, 0xffffff);

        var error = assertThrows(
                java.io.IOException.class,
                () -> DziValidator.validate(temporaryDirectory, 512, 512));

        assertEquals("Derivative preview is blank or near-blank", error.getMessage());
    }

    private static void writeJpeg(Path path, int width, int height) throws Exception {
        writeJpeg(path, width, height, 0);
    }

    private static void writeJpeg(Path path, int width, int height, int rgb) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(rgb));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ImageIO.write(image, "jpg", path.toFile());
    }
}
