package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MoticMdsEngineTest {
    @TempDir Path temporaryDirectory;

    @Test
    void opensPyramidalMoticCompoundDocumentAndReadsPixels() throws Exception {
        var source = temporaryDirectory.resolve("sample.mds");
        try (var filesystem = new POIFSFileSystem()) {
            var dsi = filesystem.getRoot().createDirectory("DSI0");
            var full = dsi.createDirectory("1.000000");
            full.createDocument("0000_0000", jpeg(Color.RED));
            full.createDocument("0001_0000", jpeg(Color.GREEN));
            var overview = dsi.createDirectory("0.500000");
            overview.createDocument("0000_0000", jpeg(Color.BLUE));
            try (var output = java.nio.file.Files.newOutputStream(source)) {
                filesystem.writeFilesystem(output);
            }
        }

        try (var engine = new MoticMdsEngine()) {
            var probe = engine.probe(source);
            var series = engine.inspect(source).get(0);
            var region = engine.readRgbRegion(source, 0, 512, 0, 16, 16);
            var tile = ImageIO.read(new java.io.ByteArrayInputStream(
                    engine.readDirectTile(source, 0,
                            engine.directTileSource(source, 0).maximumLevel(), 1, 0)));

            assertEquals("Motic MDS", probe.formatName());
            assertEquals(1024, series.width());
            assertEquals(512, series.height());
            assertEquals(2, series.resolutionCount());
            assertTrue((region.interleavedRgb()[1] & 255) > 180);
            assertTrue((tile.getRGB(8, 8) & 0x00ff00) > 0x008000);
        }
    }

    private static java.io.InputStream jpeg(Color color) throws Exception {
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 512, 512);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return new java.io.ByteArrayInputStream(output.toByteArray());
    }
}
