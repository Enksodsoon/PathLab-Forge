package org.pathlab.forge.packageformat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
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

        var firstInfo = PreparedPackageBuilder.build(derivative, 1, 1, first);
        var secondInfo = PreparedPackageBuilder.build(derivative, 1, 1, second);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertEquals(firstInfo.sha256(), secondInfo.sha256());
        assertEquals(3, firstInfo.derivativeFileCount());
    }

    private static void writeJpeg(Path path) throws Exception {
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", path.toFile());
    }
}
