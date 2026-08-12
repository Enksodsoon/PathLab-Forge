package org.pathlab.forge.analysis;

import org.pathlab.forge.conversion.RgbRegion;

public final class ClassicalAnalysis {
    private ClassicalAnalysis() {}

    public static QcResult qualityControl(RgbRegion region) {
        var rgb = region.interleavedRgb();
        var pixels = region.width() * region.height();
        double sum = 0;
        double squared = 0;
        double gradient = 0;
        int tissue = 0;
        int pen = 0;
        int dark = 0;
        for (var index = 0; index < pixels; index++) {
            var red = rgb[index * 3] & 0xff;
            var green = rgb[index * 3 + 1] & 0xff;
            var blue = rgb[index * 3 + 2] & 0xff;
            var luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
            sum += luminance;
            squared += luminance * luminance;
            if (luminance < 0.88) tissue++;
            if (blue > red * 1.35 && blue > green * 1.15 && blue > 100) pen++;
            if (luminance < 0.12) dark++;
            if (index % region.width() != 0) {
                var previous = (0.2126 * (rgb[(index - 1) * 3] & 0xff)
                        + 0.7152 * (rgb[(index - 1) * 3 + 1] & 0xff)
                        + 0.0722 * (rgb[(index - 1) * 3 + 2] & 0xff)) / 255.0;
                gradient += Math.abs(luminance - previous);
            }
        }
        var mean = sum / pixels;
        return new QcResult(
                mean,
                Math.sqrt(Math.max(0, squared / pixels - mean * mean)),
                gradient / Math.max(1, pixels - region.height()),
                (double) tissue / pixels,
                (double) pen / pixels,
                (double) dark / pixels,
                pixels);
    }

    public static TissueResult detectTissue(RgbRegion region, double luminanceThreshold) {
        if (!Double.isFinite(luminanceThreshold) || luminanceThreshold <= 0 || luminanceThreshold >= 1) {
            throw new IllegalArgumentException("Tissue threshold must be between zero and one");
        }
        var rgb = region.interleavedRgb();
        var minX = region.width();
        var minY = region.height();
        var maxX = -1;
        var maxY = -1;
        var count = 0;
        for (var y = 0; y < region.height(); y++) {
            for (var x = 0; x < region.width(); x++) {
                var index = (y * region.width() + x) * 3;
                var luminance = (0.2126 * (rgb[index] & 0xff)
                        + 0.7152 * (rgb[index + 1] & 0xff)
                        + 0.0722 * (rgb[index + 2] & 0xff)) / 255.0;
                if (luminance < luminanceThreshold) {
                    count++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new TissueResult(
                count,
                (double) count / (region.width() * region.height()),
                count == 0 ? 0 : region.x() + minX,
                count == 0 ? 0 : region.y() + minY,
                count == 0 ? 0 : maxX - minX + 1,
                count == 0 ? 0 : maxY - minY + 1);
    }

    public static int countNucleusCandidates(RgbRegion region, int darknessThreshold) {
        if (darknessThreshold < 1 || darknessThreshold > 254) {
            throw new IllegalArgumentException("Nucleus threshold must be from 1 to 254");
        }
        var rgb = region.interleavedRgb();
        var count = 0;
        for (var y = 1; y < region.height() - 1; y += 2) {
            for (var x = 1; x < region.width() - 1; x += 2) {
                var index = (y * region.width() + x) * 3;
                var value = ((rgb[index] & 0xff) + (rgb[index + 1] & 0xff) + (rgb[index + 2] & 0xff)) / 3;
                if (value >= darknessThreshold) continue;
                var left = ((rgb[index - 3] & 0xff) + (rgb[index - 2] & 0xff) + (rgb[index - 1] & 0xff)) / 3;
                var right = ((rgb[index + 3] & 0xff) + (rgb[index + 4] & 0xff) + (rgb[index + 5] & 0xff)) / 3;
                if (value <= left && value <= right) count++;
            }
        }
        return count;
    }

    public static boolean classifyPixel(int red, int green, int blue, double positiveLuminance, double negativeLuminance) {
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255
                || !Double.isFinite(positiveLuminance) || !Double.isFinite(negativeLuminance)
                || positiveLuminance == negativeLuminance) {
            throw new IllegalArgumentException("Pixel classifier parameters are invalid");
        }
        var luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
        return Math.abs(luminance - positiveLuminance) < Math.abs(luminance - negativeLuminance);
    }

    public static AffineTransform affine(double[][] source, double[][] target) {
        if (source.length != 3 || target.length != 3) {
            throw new IllegalArgumentException("Affine registration requires exactly three landmark pairs");
        }
        var matrix = new double[][] {
            {source[0][0], source[0][1], 1},
            {source[1][0], source[1][1], 1},
            {source[2][0], source[2][1], 1}
        };
        var inverse = invert3(matrix);
        var x = multiply(inverse, new double[] {target[0][0], target[1][0], target[2][0]});
        var y = multiply(inverse, new double[] {target[0][1], target[1][1], target[2][1]});
        return new AffineTransform(x[0], x[1], x[2], y[0], y[1], y[2]);
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        return new double[] {
            matrix[0][0] * vector[0] + matrix[0][1] * vector[1] + matrix[0][2] * vector[2],
            matrix[1][0] * vector[0] + matrix[1][1] * vector[1] + matrix[1][2] * vector[2],
            matrix[2][0] * vector[0] + matrix[2][1] * vector[1] + matrix[2][2] * vector[2]
        };
    }

    private static double[][] invert3(double[][] m) {
        var d = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        if (Math.abs(d) < 1e-12) throw new IllegalArgumentException("Registration landmarks are collinear");
        return new double[][] {
            {(m[1][1] * m[2][2] - m[1][2] * m[2][1]) / d, (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / d, (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / d},
            {(m[1][2] * m[2][0] - m[1][0] * m[2][2]) / d, (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / d, (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / d},
            {(m[1][0] * m[2][1] - m[1][1] * m[2][0]) / d, (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / d, (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / d}
        };
    }

    public record QcResult(double meanBrightness, double contrast, double focusGradient,
                           double tissueFraction, double bluePenFraction, double darkFoldFraction,
                           int sampledPixels) {}
    public record TissueResult(int tissuePixels, double tissueFraction, int x, int y, int width, int height) {}
    public record AffineTransform(double a, double b, double c, double d, double e, double f) {
        public double[] apply(double x, double y) { return new double[] {a * x + b * y + c, d * x + e * y + f}; }
    }
}
