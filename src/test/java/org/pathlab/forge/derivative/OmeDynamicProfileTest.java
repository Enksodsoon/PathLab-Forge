package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OmeDynamicProfileTest {
    @Test
    void dynamicProfileUsesMeasuredFactorFour512JpegRgb() {
        var profile = OmeDynamicProfile.V1;

        assertEquals("ome-dynamic-v1", profile.id());
        assertEquals(512, profile.tileSize());
        assertEquals(4, profile.pyramidFactor());
        assertEquals("jpeg", profile.codec());
        assertEquals("sRGB", profile.colorSpace());
        assertEquals(8, profile.bitsPerSample());
        assertTrue(profile.stripSensitiveMetadata());
        assertEquals(75, profile.defaultJpegQuality());
    }
}
