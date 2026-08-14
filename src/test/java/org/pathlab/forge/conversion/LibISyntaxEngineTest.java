package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LibISyntaxEngineTest {
    @Test void parsesOpenSourceDecoderMetadata() throws Exception {
        var metadata = LibISyntaxEngine.parseMetadata("""
                {"width":37382,"height":73222,"levels":8,
                 "mppX":0.25,"mppY":0.25,
                 "levelDimensions":[[37382,73222],[18691,36611],[292,572]]}
                """);
        assertEquals(37382, metadata.width());
        assertEquals(73222, metadata.height());
        assertEquals(8, metadata.levels());
        assertEquals(0.25, metadata.mppX());
        assertEquals(3, metadata.levelDimensions().size());
    }

    @Test void remainsUnavailableWithoutPinnedPythonAndPackage() {
        var engine = new LibISyntaxEngine(
                Path.of("Z:/pathlab/missing-python.exe"),
                Path.of("Z:/pathlab/missing-isyntax-runtime"),
                Path.of("Z:/pathlab/missing-bridge.py"));
        assertFalse(engine.available());
    }
}
