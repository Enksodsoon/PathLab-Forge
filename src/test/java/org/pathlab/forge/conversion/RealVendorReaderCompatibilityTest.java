package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Opt-in pixel-decode checks for licensed/local fixtures that must never enter the repository. */
class RealVendorReaderCompatibilityTest {
    @Test void opensAndTilesRealMoticMds() throws Exception {
        var configured = System.getProperty("pathlab.forge.test.mds", "");
        assumeTrue(!configured.isBlank());
        var source = Path.of(configured);
        assumeTrue(Files.isRegularFile(source));
        try (var engine = new MoticMdsEngine()) {
            assertEquals("Motic MDS", engine.probe(source).formatName());
            var info = engine.inspect(source).get(0);
            assertTrue(info.width() > 10_000 && info.height() > 10_000);
            var bytes = engine.readDirectTile(source, 0,
                    engine.directTileSource(source, 0).maximumLevel(), 0, 0);
            assertTrue(bytes.length > 1_000);
            assertTrue(ImageIO.read(new java.io.ByteArrayInputStream(bytes)).getWidth() > 0);
        }
    }

    @Test void opensAndTilesRealSdpc() throws Exception {
        var configured = System.getProperty("pathlab.forge.test.sdpc", "");
        assumeTrue(!configured.isBlank());
        var source = Path.of(configured);
        assumeTrue(Files.isRegularFile(source));
        var engine = new SdpcEngine(Path.of(System.getProperty("pathlab.forge.sdpcRuntime")));
        assertTrue(engine.available());
        assertEquals("SDPC", engine.probe(source).formatName());
        var info = engine.inspect(source).get(0);
        assertTrue(info.width() > 10_000 && info.height() > 10_000);
        var bytes = engine.readDirectTile(source, 0,
                engine.directTileSource(source, 0).maximumLevel(), 0, 0);
        assertTrue(bytes.length > 1_000);
        assertTrue(ImageIO.read(new java.io.ByteArrayInputStream(bytes)).getWidth() > 0);
    }

    @Test void opensAndTilesRealISyntaxWithFreeDecoder() throws Exception {
        var configured = System.getProperty("pathlab.forge.test.isyntax", "");
        assumeTrue(!configured.isBlank());
        var source = Path.of(configured);
        assumeTrue(Files.isRegularFile(source));
        var engine = LibISyntaxEngine.discover(source.getParent());
        assertTrue(engine.available());
        assertEquals("Philips iSyntax", engine.probe(source).formatName());
        var info = engine.inspect(source).get(0);
        assertEquals(37382, info.width());
        assertEquals(73222, info.height());
        assertEquals(8, info.resolutionCount());
        var direct = engine.directTileSource(source, 0);
        var bytes = engine.readDirectTile(source, 0, direct.maximumLevel(), 20, 50);
        assertTrue(bytes.length > 1_000);
        var image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
    }
}
