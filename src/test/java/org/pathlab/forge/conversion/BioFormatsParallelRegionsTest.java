package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.library.DatasetFormat;

class BioFormatsParallelRegionsTest {
    @Test
    void splitsTheSelectedCropIntoOrderedGapFreeHorizontalRegions() {
        var regions = BioFormatsEngine.planRegions(7, 13, 72_792, 66_004, 8);

        assertEquals(8, regions.size());
        assertEquals(new RenderRegion(7, 13, 72_792, 8_251), regions.get(0));
        assertEquals(new RenderRegion(7, 57_767, 72_792, 8_250), regions.get(7));
        assertEquals(66_004, regions.stream().mapToInt(RenderRegion::height).sum());
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
}
