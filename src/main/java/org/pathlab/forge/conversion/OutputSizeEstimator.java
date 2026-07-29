package org.pathlab.forge.conversion;

import java.math.BigInteger;

public final class OutputSizeEstimator {
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger FOUR = BigInteger.valueOf(4);

    private OutputSizeEstimator() {}

    public static long rgbPyramidUpperBound(int width, int height, double downsample) {
        if (width <= 0 || height <= 0 || downsample <= 0) {
            throw new IllegalArgumentException("Dimensions and downsample must be positive");
        }
        var outputWidth = Math.max(1, (long) Math.floor(width / downsample));
        var outputHeight = Math.max(1, (long) Math.floor(height / downsample));
        var bytes = BigInteger.valueOf(outputWidth)
                .multiply(BigInteger.valueOf(outputHeight))
                .multiply(THREE)
                .multiply(FOUR)
                .divide(BigInteger.valueOf(3));
        return bytes.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }
}
