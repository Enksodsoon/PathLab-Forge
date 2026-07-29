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
    }

    @Test
    void rejectsRedundantOrMissingDerivativeFiles() throws Exception {
        Files.writeString(temporaryDirectory.resolve("vips-properties.xml"), "redundant");

        assertThrows(
                java.io.IOException.class,
                () -> DziValidator.validate(temporaryDirectory, 1, 1));
    }

    private static void writeJpeg(Path path, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", path.toFile());
    }
}
