package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SdpcEngineTest {
    @Test void parsesNativeLevelMetadataAndRemovesPadding() {
        var level = SdpcEngine.parseLevel(2,
                "LayerWidth=26880|LayerHeight=21504|BoundWidth=64|BoundHeight=32|XCount=40|YCount=32");
        assertEquals(2, level.index());
        assertEquals(26816, level.width());
        assertEquals(21472, level.height());
    }

    @Test void rejectsIncompleteNativeMetadata() {
        assertNull(SdpcEngine.parseLevel(0, "XCount=40|YCount=32"));
    }

    @Test void remainsUnavailableWhenRuntimeIsAbsent() {
        assertFalse(new SdpcEngine(Path.of("Z:/pathlab/missing-sdpc-runtime")).available());
    }
}
