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

    public static CompressedEstimate compressedOmeTiff(
            int width,
            int height,
            double downsample,
            long sourceBytes,
            boolean sourceIsOmeTiff) {
        if (width <= 0 || height <= 0 || downsample <= 0 || sourceBytes < 0) {
            throw new IllegalArgumentException(
                    "Dimensions, downsample and source size are invalid");
        }
        var outputWidth = Math.max(1L, (long) Math.floor(width / downsample));
        var outputHeight = Math.max(1L, (long) Math.floor(height / downsample));
        var basePixels = (double) outputWidth * outputHeight;
        var pyramidPixels = basePixels * 4.0 / 3.0;
        var wholeSlideFactor = Math.max(
                0.0,
                Math.min(1.0, Math.log10(Math.max(1.0, basePixels / 1_000_000.0)) / 3.0));
        var lowerBytesPerPyramidPixel = 0.25 + ((0.025 - 0.25) * wholeSlideFactor);
        var upperBytesPerPyramidPixel = 1.20 + ((0.65 - 1.20) * wholeSlideFactor);
        var pixelLower = saturatedRound(pyramidPixels * lowerBytesPerPyramidPixel);
        var pixelUpper = saturatedRound(pyramidPixels * upperBytesPerPyramidPixel);
        var scaledSource = sourceBytes == 0 ? 0 : saturatedRound(sourceBytes / (downsample * downsample));

        long lower;
        long upper;
        long expected;
        if (sourceIsOmeTiff && scaledSource > 0) {
            lower = Math.max(pixelLower, saturatedRound(scaledSource * 0.5));
            upper = Math.max(lower, Math.min(pixelUpper, saturatedRound(scaledSource * 2.0)));
            expected = clamp(scaledSource, lower, upper);
        } else {
            lower = pixelLower;
            var sourceCeiling = scaledSource == 0
                    ? pixelUpper
                    : saturatedRound(scaledSource * 1.25);
            upper = Math.max(lower, Math.min(pixelUpper, sourceCeiling));
            expected = saturatedRound(Math.sqrt((double) lower * upper));
        }
        return new CompressedEstimate(expected, lower, upper);
    }

    private static long saturatedRound(double value) {
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1, Math.round(value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record CompressedEstimate(long expectedBytes, long lowerBytes, long upperBytes) {
        public CompressedEstimate {
            if (expectedBytes <= 0
                    || lowerBytes <= 0
                    || upperBytes < lowerBytes
                    || expectedBytes < lowerBytes
                    || expectedBytes > upperBytes) {
                throw new IllegalArgumentException("Compressed output estimate is invalid");
            }
        }
    }
}
