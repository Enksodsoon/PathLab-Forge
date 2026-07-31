package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VipsRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsToHighestCalibratedCandidateUntilQualityEvidenceAllowsLowerValue() {
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

    @Test
    void appliesBoundedConcurrencyAndCacheToEveryVipsProcess() {
        var command = VipsRuntime.commandLine(
                Path.of("vips.exe"), List.of("dzsave", "input.tif", "output"));

        assertEquals("vips.exe", command.get(0));
        assertEquals("--vips-concurrency=5", command.get(1));
        assertEquals("--vips-cache-max-memory=1073741824", command.get(2));
        assertEquals("--vips-cache-max-files=192", command.get(3));
        assertEquals("--vips-cache-max=128", command.get(4));
        assertEquals(List.of("dzsave", "input.tif", "output"), command.subList(5, 8));
    }

    @Test
    void usesCompactNonProgressiveFourTwentyJpegEncoderProfile() {
        var suffix = VipsRuntime.compactJpegSuffix(75);

        assertTrue(suffix.contains("subsample-mode=on"));
        assertTrue(suffix.contains("optimize-coding=true"));
        assertTrue(suffix.contains("trellis-quant=true"));
        assertTrue(suffix.contains("overshoot-deringing=true"));
        assertTrue(suffix.contains("interlace=false"));
        assertTrue(suffix.contains("strip"));
        var fallback = VipsRuntime.jpegSuffix(75, "compact-420-optimized");
        assertTrue(fallback.contains("optimize-coding=true"));
        assertTrue(!fallback.contains("trellis-quant"));
        var rescue = VipsRuntime.jpegSuffix(80, "compact-444-quality-rescue");
        assertTrue(rescue.contains("subsample-mode=off"));
    }

    @Test
    void distributesNonIntegerResampleGeometryWithoutCroppingOrAddingRows() {
        var heights = VipsRuntime.targetRegionHeights(
                List.of(7_512, 7_512, 7_512, 7_512, 7_512, 7_512, 7_512, 7_511),
                50_078);

        assertEquals(8, heights.size());
        assertEquals(50_078, heights.stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of(6_260, 6_260, 6_260, 6_259, 6_260, 6_260, 6_260, 6_259), heights);
    }

    @Test
    void readsMachineValueAfterWindowsTiffWarnings() throws IOException {
        var output = """
                (vipsheader.exe:43872): VIPS-WARNING **: Auto-corrected TIFF values [2,2]

                68753
                """;

        assertEquals(68_753, VipsRuntime.parseIntegerOutput(output));
        assertThrows(
                IOException.class,
                () -> VipsRuntime.parseIntegerOutput("VIPS-WARNING: no property returned"));
    }

    private static String escaped(Path path) {
        return path.toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/')
                .replace(" ", "\\ ");
    }
}
