package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.conversion.RgbRegion;

final class PathologyToolsTest {
    @Test
    void createsABoundedTmaGrid() {
        var cores = TmaGrid.create(UUID.randomUUID().toString(), "", 2, 3, 10, 20, 300, 200);

        assertEquals(6, cores.size());
        assertEquals(PathObject.Kind.TMA_CORE, cores.get(0).kind());
        assertEquals("1", cores.get(5).properties().get("row"));
    }

    @Test
    void estimatesStainDirectionAndProducesOnlyABoundedPreview() {
        var pixels = new byte[8 * 8 * 3];
        for (var index = 0; index < pixels.length; index += 3) {
            pixels[index] = 90;
            pixels[index + 1] = 45;
            pixels[index + 2] = 110;
        }
        var region = new RgbRegion(0, 0, 8, 8, pixels);

        var vector = StainTools.estimateOpticalDensityVector(region);
        var normalized = StainTools.normalizePreview(region, 180, 160, 190);

        assertEquals(1, Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]), 0.0001);
        assertTrue(normalized.interleavedRgb().length == pixels.length);
    }
}
