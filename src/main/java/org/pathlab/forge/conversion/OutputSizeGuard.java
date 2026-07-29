package org.pathlab.forge.conversion;

public final class OutputSizeGuard {
    private static final long MINIMUM_ALLOWANCE = 512L * 1024 * 1024;

    private OutputSizeGuard() {}

    public static long maximumSuitableBytes(
            long sourceBytes, int outputWidth, int outputHeight, double downsample) {
        if (sourceBytes <= 0 || outputWidth <= 0 || outputHeight <= 0 || downsample <= 0) {
            throw new IllegalArgumentException("Source, dimensions and downsample must be positive");
        }
        var rawRgbBytes = Math.multiplyExact(
                Math.multiplyExact((long) outputWidth, outputHeight), 3L);
        var sourceAllowance = Math.multiplyExact(sourceBytes, 3L) / 4L;
        var rawAllowance = rawRgbBytes / 6L;
        return Math.max(
                MINIMUM_ALLOWANCE,
                Math.min(sourceAllowance, rawAllowance));
    }

    public static void requireSuitable(
            long outputBytes,
            long sourceBytes,
            int outputWidth,
            int outputHeight,
            double downsample) {
        var maximum = maximumSuitableBytes(
                sourceBytes, outputWidth, outputHeight, downsample);
        if (outputBytes > maximum) {
            throw new IllegalStateException(
                    "Rendered RGB output ballooned to "
                            + outputBytes
                            + " bytes; expected no more than "
                            + maximum
                            + " bytes for this source and scale");
        }
    }
}
