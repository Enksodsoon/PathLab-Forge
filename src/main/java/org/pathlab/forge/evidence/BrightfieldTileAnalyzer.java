package org.pathlab.forge.evidence;

import java.awt.image.BufferedImage;
import java.util.Set;

/** Bounded deterministic hematoxylin-component and DAB descriptor baseline. */
public final class BrightfieldTileAnalyzer {
    private static final Set<String> KNOWN = Set.of("er", "pr", "ki-67", "her2", "pd-l1");

    private BrightfieldTileAnalyzer() {}

    public static Result analyze(BufferedImage image, String requestedMarker) {
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || (long) image.getWidth() * image.getHeight() > 4_194_304) {
            throw new IllegalArgumentException("Brightfield image geometry is invalid");
        }
        var marker = requestedMarker == null ? "generic" : requestedMarker.toLowerCase();
        if (!KNOWN.contains(marker)) marker = "generic";
        var width = image.getWidth();
        var height = image.getHeight();
        var dabPixels = 0;
        double dabOd = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var rgb = image.getRGB(x, y);
                var red = rgb >>> 16 & 0xff;
                var green = rgb >>> 8 & 0xff;
                var blue = rgb & 0xff;
                if (red - green >= 15 && green - blue >= 15 && red + green + blue < 660) {
                    dabPixels++;
                    var luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
                    dabOd += -Math.log((luminance + 1) / 256.0);
                }
            }
        }
        var segmentation = OpticalDensityWatershed.segment(image);
        var instances = segmentation.instances();
        return new Result(
                marker,
                compartment(marker),
                instances.size(),
                average(instances, OpticalDensityWatershed.Instance::areaPx2),
                average(instances, OpticalDensityWatershed.Instance::perimeterPx),
                average(instances, OpticalDensityWatershed.Instance::eccentricity),
                average(instances, OpticalDensityWatershed.Instance::solidity),
                (double) dabPixels / (width * height),
                dabPixels == 0 ? 0 : dabOd / dabPixels,
                true,
                instances);
    }

    private static Double average(java.util.List<OpticalDensityWatershed.Instance> instances,
            java.util.function.ToDoubleFunction<OpticalDensityWatershed.Instance> getter) {
        return instances.isEmpty() ? null : instances.stream().mapToDouble(getter).average().orElseThrow();
    }

    private static String compartment(String marker) {
        return switch (marker) {
            case "er", "pr", "ki-67" -> "nuclear";
            case "her2" -> "membrane";
            case "pd-l1" -> "tumor-immune-region";
            default -> "generic-region";
        };
    }

    public record Result(
            String marker,
            String compartment,
            int cellCount,
            Double meanNucleusAreaPx2,
            Double meanNucleusPerimeterPx,
            Double meanNucleusEccentricity,
            Double meanNucleusSolidity,
            double dabAreaFraction,
            double meanDabOd,
            boolean researchEstimate,
            java.util.List<OpticalDensityWatershed.Instance> instances) {}
}
