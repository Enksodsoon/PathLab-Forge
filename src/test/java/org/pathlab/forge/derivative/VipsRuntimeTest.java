package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class VipsRuntimeTest {
    @Test
    void usesQuPathSizedQualityForWholeSlidesAndHigherQualityForCrops() {
        assertEquals(75, VipsRuntime.omeJpegQuality(82_922, 45_367));
        assertEquals(93, VipsRuntime.omeJpegQuality(7_557, 7_360));
    }

    @Test
    void serializesRegionPathsForTheVipsArrayParserIncludingSpaces() {
        var serialized = VipsRuntime.serializeImageArray(List.of(
                Path.of("C:\\Temp Folder\\region-00.ome.tif"),
                Path.of("C:\\Temp Folder\\region-01.ome.tif")));

        assertEquals(
                "C:/Temp\\ Folder/region-00.ome.tif C:/Temp\\ Folder/region-01.ome.tif",
                serialized);
    }
}
