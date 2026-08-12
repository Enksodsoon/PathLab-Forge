package org.pathlab.forge.analysis;

import org.pathlab.forge.conversion.RgbRegion;

public final class StainTools {
    private StainTools() {}

    public static double[] estimateOpticalDensityVector(RgbRegion region) {
        var rgb = region.interleavedRgb();
        double red = 0;
        double green = 0;
        double blue = 0;
        int count = 0;
        for (var index = 0; index < rgb.length; index += 3) {
            var r = rgb[index] & 0xff;
            var g = rgb[index + 1] & 0xff;
            var b = rgb[index + 2] & 0xff;
            if ((r + g + b) / 3.0 > 235) continue;
            red += -Math.log((r + 1.0) / 256.0);
            green += -Math.log((g + 1.0) / 256.0);
            blue += -Math.log((b + 1.0) / 256.0);
            count++;
        }
        if (count < 16) throw new IllegalArgumentException("Not enough stained pixels for estimation");
        var length = Math.sqrt(red * red + green * green + blue * blue);
        return new double[] {red / length, green / length, blue / length};
    }

    public static RgbRegion normalizePreview(RgbRegion region, int targetRed, int targetGreen, int targetBlue) {
        if (targetRed < 1 || targetRed > 255 || targetGreen < 1 || targetGreen > 255
                || targetBlue < 1 || targetBlue > 255) {
            throw new IllegalArgumentException("Normalization target is invalid");
        }
        var input = region.interleavedRgb();
        var means = new double[3];
        for (var index = 0; index < input.length; index += 3) {
            means[0] += input[index] & 0xff;
            means[1] += input[index + 1] & 0xff;
            means[2] += input[index + 2] & 0xff;
        }
        var pixels = region.width() * region.height();
        for (var channel = 0; channel < 3; channel++) means[channel] /= pixels;
        var targets = new int[] {targetRed, targetGreen, targetBlue};
        var output = new byte[input.length];
        for (var index = 0; index < input.length; index++) {
            var channel = index % 3;
            var normalized = Math.round((input[index] & 0xff) * targets[channel] / Math.max(1, means[channel]));
            output[index] = (byte) Math.max(0, Math.min(255, normalized));
        }
        return new RgbRegion(region.x(), region.y(), region.width(), region.height(), output);
    }
}
