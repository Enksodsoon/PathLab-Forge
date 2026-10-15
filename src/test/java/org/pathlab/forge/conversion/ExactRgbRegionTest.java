package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExactRgbRegionTest {
    @Test
    void legacyReadersNeverSilentlyReadPlaneZeroForAnotherPlane() throws Exception {
        var engine = new ConversionEngine() {
            public boolean available() { return true; }
            public String runtimeDescription() { return "synthetic"; }
            public List<SeriesInfo> inspect(Path source) { return List.of(); }
            public void convert(Path source, int series, Path output) {}
            public RgbRegion readRgbRegion(Path source, int series, int x, int y, int width, int height) {
                return new RgbRegion(x, y, width, height, new byte[width * height * 3]);
            }
        };
        assertEquals(2, engine.readRgbRegion(Path.of("synthetic"), 3, 0, 0, 2, 4, 1, 1).x());
        assertThrows(IOException.class, () -> engine.readRgbRegion(Path.of("synthetic"), 3, 1, 0, 2, 4, 1, 1));
        assertThrows(IOException.class, () -> engine.readRgbRegion(Path.of("synthetic"), 3, 0, 1, 2, 4, 1, 1));
    }
}
