package org.pathlab.forge.conversion;

import java.math.BigInteger;

public final class OutputSizeEstimator {
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger FOUR = BigInteger.valueOf(4);

    private OutputSizeEstimator() {}

    public static long rgbPyramidUpperBound(int width, int height, int downsample) {
        if (width <= 0 || height <= 0 || downsample <= 0) {
            throw new IllegalArgumentException("Dimensions and downsample must be positive");
        }
        var divisor = BigInteger.valueOf(downsample).pow(2);
        var bytes = BigInteger.valueOf(width)
                .multiply(BigInteger.valueOf(height))
                .multiply(THREE)
                .multiply(FOUR)
                .divide(BigInteger.valueOf(3))
                .divide(divisor);
        return bytes.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }
}
