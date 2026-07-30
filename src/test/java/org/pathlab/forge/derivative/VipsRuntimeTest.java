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
    void defaultsToHighestCalibratedCandidateUntilQualityEvidenceAllowsLowerValue() {
        assertEquals(93, VipsRuntime.omeJpegQuality(82_922, 45_367));
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
        assertEquals("--vips-concurrency=4", command.get(1));
        assertEquals("--vips-cache-max-memory=805306368", command.get(2));
        assertEquals("--vips-cache-max-files=128", command.get(3));
        assertEquals("--vips-cache-max=100", command.get(4));
        assertEquals(List.of("dzsave", "input.tif", "output"), command.subList(5, 8));
    }

    private static String escaped(Path path) {
        return path.toAbsolutePath()
                .normalize()
                .toString()
                .replace('\\', '/')
                .replace(" ", "\\ ");
    }
}
