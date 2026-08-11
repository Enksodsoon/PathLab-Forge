package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.library.DatasetFormat;

class BioFormatsParallelRegionsTest {
    @Test
    void serializesFullSlideConversionsOnEveryHardwareProfile() {
        var gib = 1024L * 1024 * 1024;
        assertEquals(1, ConversionService.recommendedConcurrentConversions(8, 16 * gib));
        assertEquals(1, ConversionService.recommendedConcurrentConversions(12, 12 * gib));
        assertEquals(1, ConversionService.recommendedConcurrentConversions(12, 16 * gib));
        assertEquals(1, ConversionService.recommendedConcurrentConversions(32, 64 * gib));
    }

    @Test
    void locksEveryNewConversionRequestToTheFastDirectOmeRoute() {
        assertEquals(
                ArtifactRevisionFormat.PREPARED_DZI_V2,
                ConversionService.selectedConversionFormat(ArtifactRevisionFormat.OME_DYNAMIC_V1));
        assertEquals(
                ArtifactRevisionFormat.PREPARED_DZI_V2,
                ConversionService.selectedConversionFormat(ArtifactRevisionFormat.PREPARED_DZI_V2));

        assertTrue(ConversionService.shouldUseQuPathWriter(
                ArtifactRevisionFormat.OME_DYNAMIC_V1, true));
        assertTrue(!ConversionService.shouldUseQuPathWriter(
                ArtifactRevisionFormat.PREPARED_DZI_V2, true));

        assertTrue(ConversionService.shouldUseDirectDzi(
                ArtifactRevisionFormat.PREPARED_DZI_V2, true, true, true));
        assertTrue(!ConversionService.shouldUseDirectDzi(
                ArtifactRevisionFormat.OME_DYNAMIC_V1, true, true, true));
        assertTrue(!ConversionService.shouldUseDirectDzi(
                ArtifactRevisionFormat.PREPARED_DZI_V2, false, true, true));
        assertTrue(ConversionService.requiresDynamicOmeProfile(
                ArtifactRevisionFormat.OME_DYNAMIC_V1));
        assertTrue(!ConversionService.requiresDynamicOmeProfile(
                ArtifactRevisionFormat.PREPARED_DZI_V2));

        var prepared = ConversionService.conversionStartDetail(
                ArtifactRevisionFormat.PREPARED_DZI_V2,
                false,
                true,
                "adaptive-12c-32gb");
        assertTrue(prepared.contains("direct DZI"));
        assertTrue(!prepared.contains("Direct tiled OME"));
    }

    @Test
    void splitsTheSelectedCropIntoOrderedGapFreeHorizontalRegions() {
        var regions = BioFormatsEngine.planRegions(7, 13, 72_792, 66_004, 8);

        assertEquals(8, regions.size());
        assertEquals(7, regions.get(0).x());
        assertEquals(13, regions.get(0).y());
        assertEquals(66_004, regions.stream().mapToInt(RenderRegion::height).sum());
        assertTrue(regions.stream()
                .allMatch(region -> (long) region.width() * region.height() <= 1_600_000_000L));
        for (var index = 1; index < regions.size(); index++) {
            var previous = regions.get(index - 1);
            assertEquals(previous.y() + previous.height(), regions.get(index).y());
        }
    }

    @Test
    void neverCreatesMoreRegionsThanRows() {
        var regions = BioFormatsEngine.planRegions(0, 0, 20, 3, 8);

        assertEquals(3, regions.size());
        assertTrue(regions.stream().allMatch(region -> region.height() == 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> BioFormatsEngine.planRegions(0, 0, 20, 3, 0));
    }

    @Test
    void selectsLightningRgbOnlyForLargeSupportedVsiExports() {
        var large = new ConversionRequest(
                Path.of("slide.vsi"), 3, 0, 0, 72_792, 66_004, 72_792, 66_004, 1.5);
        var small = new ConversionRequest(
                Path.of("slide.vsi"), 3, 0, 0, 11_336, 11_040, 72_792, 66_004, 1.5);

        assertTrue(ConversionService.shouldUseParallelRgb(
                DatasetFormat.VSI, true, true, 11, large));
        assertTrue(!ConversionService.shouldUseParallelRgb(
                DatasetFormat.VSI, true, true, 11, small));
        assertTrue(!ConversionService.shouldUseParallelRgb(
                DatasetFormat.OME_TIFF, true, true, 11, large));
        assertTrue(!ConversionService.shouldUseParallelRgb(
                DatasetFormat.VSI, false, true, 11, large));
        assertTrue(!ConversionService.shouldUseParallelRgb(
                DatasetFormat.VSI, true, false, 11, large));
    }

    @Test
    void capsWorkersForSixCoreTargetAndUsesTwoForSlowSources() {
        assertEquals(5, ConversionService.parallelRgbWorkers(6, 9, false));
        assertEquals(2, ConversionService.parallelRgbWorkers(6, 9, true));
        assertEquals(3, ConversionService.parallelRgbWorkers(4, 5, false));
        assertEquals(1, ConversionService.parallelRgbWorkers(1, 5, false));
        assertEquals(6, ConversionService.parallelRgbWorkers(12, 11, false, 8));
    }

    @Test
    void choosesNearbyRegionCountThatDividesFinalHeightExactly() {
        assertEquals(
                7,
                BioFormatsEngine.preferredRegionCount(
                        103_130, 75_118, 50_078, 5));
        assertEquals(
                5,
                BioFormatsEngine.preferredRegionCount(
                        50_000, 50_000, 50_003, 5));
        assertTrue(ConversionService.canReuseVerifiedRegions(true, 31_087, 7));
        assertTrue(!ConversionService.canReuseVerifiedRegions(true, 31_087, 4));
        assertTrue(ConversionService.canReuseVerifiedRegions(false, 31_087, 4));
    }

    @Test
    void mapsTopLevelSeriesAndResolutionToFlattenedReaderIndexWithoutARescan() {
        var resolutionCounts = new int[] {6, 7, 10, 1};

        assertEquals(0, BioFormatsEngine.flattenedIndex(0, 0, resolutionCounts));
        assertEquals(13, BioFormatsEngine.flattenedIndex(2, 0, resolutionCounts));
        assertEquals(22, BioFormatsEngine.flattenedIndex(2, 9, resolutionCounts));
        assertEquals(23, BioFormatsEngine.flattenedIndex(3, 0, resolutionCounts));
    }

    @Test
    void fallbackChoosesTheNativePyramidScaleClosestToRequestedScale() {
        assertTrue(
                BioFormatsEngine.resolutionScaleDistance(2.0, 1.5)
                        < BioFormatsEngine.resolutionScaleDistance(1.0, 1.5));
        assertTrue(
                BioFormatsEngine.resolutionScaleDistance(2.0, 2.0)
                        < BioFormatsEngine.resolutionScaleDistance(4.0, 2.0));
    }
}
