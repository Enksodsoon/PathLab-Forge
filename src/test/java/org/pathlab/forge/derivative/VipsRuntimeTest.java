package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VipsRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void usesQuPathSizedQualityForWholeSlidesAndHigherQualityForCrops() {
        assertEquals(75, VipsRuntime.omeJpegQuality(82_922, 45_367));
        assertEquals(93, VipsRuntime.omeJpegQuality(7_557, 7_360));
    }

    @Test
    void serializesRegionPathsForTheVipsArrayParserIncludingSpaces() {
        var first = temporaryDirectory.resolve("Temp Folder").resolve("region-00.ome.tif");
        var second = temporaryDirectory.resolve("Temp Folder").resolve("region-01.ome.tif");
        var serialized = VipsRuntime.serializeImageArray(List.of(first, second));

        assertEquals(
                escaped(first) + " " + escaped(second),
                serialized);
    }

    private static String escaped(Path path) {
        return path.toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/')
                .replace(" ", "\\ ");
    }
}
