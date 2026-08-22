package org.pathlab.forge.evidence;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;

/** Descriptive color/OD measurements for supported brightfield special stains and cytology. */
public final class SpecialStainAnalyzer {
    private static final Set<String> SUPPORTED = Set.of(
            "pas", "pas_d", "trichrome", "gms", "afb", "papanicolaou", "generic_brightfield");
    private SpecialStainAnalyzer() { }

    public static Result analyze(BufferedImage image, String requestedStain) {
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new IllegalArgumentException("Special-stain image geometry is invalid");
        }
        var stain = requestedStain == null ? "generic_brightfield" : requestedStain.toLowerCase();
        var fallback = !SUPPORTED.contains(stain);
        if (fallback) stain = "generic_brightfield";
        var stained = 0;
        var tissue = 0;
        var saturated = 0;
        double od = 0;
        for (var y = 0; y < image.getHeight(); y++) for (var x = 0; x < image.getWidth(); x++) {
            var rgb = image.getRGB(x, y);
            var red = rgb >>> 16 & 0xff;
            var green = rgb >>> 8 & 0xff;
            var blue = rgb & 0xff;
            if ((red + green + blue) / 3.0 < 235) tissue++;
            if (red < 3 || green < 3 || blue < 3) saturated++;
            if (matches(stain, red, green, blue)) {
                stained++;
                var luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
                od += -Math.log((luminance + 1) / 256.0);
            }
        }
        var reasons = new java.util.ArrayList<String>();
        if (tissue < image.getWidth() * image.getHeight() * 0.05) reasons.add("insufficient_tissue");
        if (saturated > image.getWidth() * image.getHeight() * 0.35) reasons.add("excessive_saturation");
        var measurement = Map.<String, Object>of(
                "stainAreaFraction", tissue == 0 ? 0 : (double) stained / tissue,
                "meanStainOd", stained == 0 ? 0 : od / stained,
                "tissuePixelCount", tissue,
                "stainedPixelCount", stained);
        return new Result(stain, fallback ? "generic-descriptive" : mode(stain), measurement,
                fallback ? "UNSUPPORTED_STAIN_GENERIC_FALLBACK" : reasons.isEmpty() ? null : "STAIN_QC_FAILED",
                java.util.List.copyOf(reasons), reasons.isEmpty() ? 0.15 : 1.0);
    }

    private static boolean matches(String stain, int red, int green, int blue) {
        return switch (stain) {
            case "pas", "pas_d" -> red - green >= 30 && blue - green >= 15;
            case "trichrome" -> blue - red >= 20 || green - red >= 20;
            case "gms" -> red < 80 && green < 80 && blue < 80;
            case "afb" -> red - green >= 50 && red - blue >= 40;
            case "papanicolaou" -> Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) >= 35;
            default -> red + green + blue < 600;
        };
    }

    private static String mode(String stain) {
        return "papanicolaou".equals(stain) ? "cytology-descriptive" : "special-stain-descriptive";
    }

    public record Result(String stainId, String analysisMode, Map<String, Object> measurements,
            String abstentionReason, java.util.List<String> qc, double uncertainty) { }
}
