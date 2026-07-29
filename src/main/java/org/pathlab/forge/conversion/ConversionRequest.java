package org.pathlab.forge.conversion;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public record ConversionRequest(
        Path source,
        int seriesIndex,
        int cropX,
        int cropY,
        int cropWidth,
        int cropHeight,
        int seriesWidth,
        int seriesHeight,
        int downsample) {
    private static final Set<Integer> SUPPORTED_DOWNSAMPLES = Set.of(1, 2, 4, 8);

    public ConversionRequest {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (seriesIndex < 0) {
            throw new IllegalArgumentException("Series index must not be negative");
        }
        if (seriesWidth <= 0 || seriesHeight <= 0) {
            throw new IllegalArgumentException("Series dimensions must be positive");
        }
        if (cropX < 0
                || cropY < 0
                || cropWidth <= 0
                || cropHeight <= 0
                || (long) cropX + cropWidth > seriesWidth
                || (long) cropY + cropHeight > seriesHeight) {
            throw new IllegalArgumentException("Crop must be inside the selected image series");
        }
        if (!SUPPORTED_DOWNSAMPLES.contains(downsample)) {
            throw new IllegalArgumentException("Downsample must be 1x, 2x, 4x, or 8x");
        }
    }

    public int outputWidth() {
        return Math.max(1, (cropWidth + downsample - 1) / downsample);
    }

    public int outputHeight() {
        return Math.max(1, (cropHeight + downsample - 1) / downsample);
    }

    public long estimatedRgbPyramidBytes() {
        return OutputSizeEstimator.rgbPyramidUpperBound(cropWidth, cropHeight, downsample);
    }

    public boolean isFullSeries() {
        return cropX == 0
                && cropY == 0
                && cropWidth == seriesWidth
                && cropHeight == seriesHeight;
    }
}
