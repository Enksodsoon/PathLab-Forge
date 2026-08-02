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
    @Test
    void acceptsAProfileWithoutStoredSubifdsWhenTheFirstFactorFourLevelFitsOneTile() {
        assertEquals(2, VipsRuntime.expectedStoredSubifds(2048, 1536, OmeDynamicProfile.V1));
        assertEquals(3, VipsRuntime.expectedStoredSubifds(2049, 1536, OmeDynamicProfile.V1));
        assertEquals(5, VipsRuntime.expectedStoredSubifds(8193, 4096, OmeDynamicProfile.V1));
    }

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
    void dynamicOmeOptionsUseTheApprovedTiledJpegProfileAndStripMetadata() throws Exception {
        var method = java.util.Arrays.stream(VipsRuntime.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("omeTiffOptions"))
                .findFirst();
        assertTrue(method.isPresent(), "VipsRuntime must expose dynamic OME save options");
        method.orElseThrow().setAccessible(true);

        assertEquals(
                "[pyramid,tile,tile-width=512,tile-height=512,"
                        + "depth=onetile,compression=jpeg,Q=75,bigtiff,subifd,properties=false]",
                method.orElseThrow().invoke(null, OmeDynamicProfile.V1, 75));
    }

    @Test
    void reservesCpuForTheViewerWhenHigherSpecHardwareRunsTwoJobs() {
        var previousProcessors = System.getProperty("pathlab.forge.runtime.processors");
        var previousMemory = System.getProperty("pathlab.forge.runtime.memoryBytes");
        try {
            System.setProperty("pathlab.forge.runtime.processors", "12");
            System.setProperty(
                    "pathlab.forge.runtime.memoryBytes",
                    Long.toString(32L * 1024 * 1024 * 1024));

            var command = VipsRuntime.commandLine(
                    Path.of("vips.exe"), List.of("dzsave", "input.tif", "output"));

            assertEquals("--vips-concurrency=5", command.get(1));
            assertTrue(VipsRuntime.parallelQualityProfiles());
        } finally {
            restoreProperty("pathlab.forge.runtime.processors", previousProcessors);
            restoreProperty("pathlab.forge.runtime.memoryBytes", previousMemory);
        }
    }

    @Test
    void boundsEachParallelQualityProbeToOneVipsThreadAndASharedCacheSlice() {
        var command = VipsRuntime.probeCommandLine(
                Path.of("vips.exe"),
                List.of("crop", "input.tif", "roi.png", "0", "0", "256", "256"),
                5);

        assertEquals("--vips-concurrency=1", command.get(1));
        assertEquals("--vips-cache-max-memory=214748364", command.get(2));
        assertEquals("--vips-cache-max-files=32", command.get(3));
        assertEquals("crop", command.get(5));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void launchesPersistentNativeRoiExtractionInAnIsolatedBoundedJvm() {
        var first = new AdaptiveJpegQualitySelector.Roi(0, 1, 256, 255);
        var output = temporaryDirectory.resolve("quality-rois").resolve("roi-00.png");

        var command = VipsRuntime.nativeRoiCommandLine(
                temporaryDirectory.resolve("vips.exe"),
                temporaryDirectory.resolve("source.ome.tif"),
                List.of(first),
                List.of(output));

        assertTrue(command.get(0).endsWith("java.exe") || command.get(0).endsWith("java"));
        assertEquals("-Xmx192m", command.get(2));
        assertEquals(VipsNativeRoiHelper.class.getName(), command.get(5));
        assertEquals("roi-00.png", command.get(10));
        assertEquals(List.of("0", "1", "256", "255"), command.subList(11, 15));
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
        var rescue = VipsRuntime.jpegSuffix(95, "compact-444-quality-rescue");
        assertTrue(rescue.contains("Q=95"));
        assertTrue(rescue.contains("subsample-mode=off"));
        assertThrows(
                IllegalArgumentException.class,
                () -> VipsRuntime.jpegSuffix(100, "compact-444-quality-rescue"));
    }

    @Test
    void choosesSmallerQualityCompliantProfileInsteadOfFirstPassingProfile() {
        var q95FourTwenty = new AdaptiveJpegQualitySelector.Selection(
                95, 0.973, 1.6, 0.91, "compact-420-trellis");
        var q80FourFourFour = new AdaptiveJpegQualitySelector.Selection(
                80, 0.971, 2.0, 0.92, "compact-444-quality-rescue");

        var selected = VipsRuntime.preferSmallerProfile(
                q95FourTwenty, 900_000, q80FourFourFour, 600_000);

        assertEquals(q80FourFourFour, selected);
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
