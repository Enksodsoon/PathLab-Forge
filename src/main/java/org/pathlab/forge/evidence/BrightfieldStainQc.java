package org.pathlab.forge.evidence;

import java.awt.image.BufferedImage;

/** Deterministic preflight for bounded brightfield H/DAB measurements. */
public final class BrightfieldStainQc {
    private BrightfieldStainQc() {}

    public static Result inspect(BufferedImage image, boolean controlsValidated) {
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new IllegalArgumentException("Brightfield image geometry is invalid");
        }
        long pixels = (long) image.getWidth() * image.getHeight();
        var nearWhite = 0L;
        var saturated = 0L;
        var blueLike = 0L;
        var brownLike = 0L;
        double channelSpread = 0;
        for (var y = 0; y < image.getHeight(); y++) for (var x = 0; x < image.getWidth(); x++) {
            var rgb = image.getRGB(x, y);
            var red = rgb >>> 16 & 0xff;
            var green = rgb >>> 8 & 0xff;
            var blue = rgb & 0xff;
            if (red > 245 && green > 245 && blue > 245) nearWhite++;
            // White background is expected in brightfield and must not be treated as clipped tissue.
            if (red < 3 || green < 3 || blue < 3) saturated++;
            if (blue - red >= 20 && blue - green >= 20) blueLike++;
            if (red - green >= 12 && green - blue >= 10) brownLike++;
            channelSpread += Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
        }
        var background = (double) nearWhite / pixels;
        var saturation = (double) saturated / pixels;
        var hematoxylin = (double) blueLike / pixels;
        var dab = (double) brownLike / pixels;
        var separation = Math.min(1, channelSpread / pixels / 64.0);
        var reasons = new java.util.ArrayList<String>();
        if (background > 0.97) reasons.add("insufficient_tissue");
        if (saturation > 0.35) reasons.add("excessive_saturation");
        if (separation < 0.03) reasons.add("weak_stain_separation");
        var status = reasons.isEmpty() ? (controlsValidated ? "calibrated" : "relative_only") : "not_evaluable";
        return new Result(status, background, saturation, hematoxylin, dab, separation, java.util.List.copyOf(reasons));
    }

    public record Result(String calibrationStatus, double backgroundFraction, double saturationFraction,
            double hematoxylinLikeFraction, double dabLikeFraction, double separationScore,
            java.util.List<String> reasons) {}
}
