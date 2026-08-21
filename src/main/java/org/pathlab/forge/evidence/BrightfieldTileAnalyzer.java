package org.pathlab.forge.evidence;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
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
        var nuclei = new boolean[width * height];
        var dabPixels = 0;
        double dabOd = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var rgb = image.getRGB(x, y);
                var red = rgb >>> 16 & 0xff;
                var green = rgb >>> 8 & 0xff;
                var blue = rgb & 0xff;
                nuclei[y * width + x] = blue - red >= 25 && blue - green >= 25
                        && red + green + blue < 660;
                if (red - green >= 15 && green - blue >= 15 && red + green + blue < 660) {
                    dabPixels++;
                    var luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
                    dabOd += -Math.log((luminance + 1) / 256.0);
                }
            }
        }
        var components = components(nuclei, width, height);
        return new Result(
                marker,
                compartment(marker),
                components.count,
                components.count == 0 ? null : (double) components.pixels / components.count,
                (double) dabPixels / (width * height),
                dabPixels == 0 ? 0 : dabOd / dabPixels,
                true);
    }

    private static Components components(boolean[] mask, int width, int height) {
        var seen = new boolean[mask.length];
        var queue = new ArrayDeque<Integer>();
        var count = 0;
        var pixels = 0;
        for (var start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;
            seen[start] = true;
            queue.add(start);
            var size = 0;
            while (!queue.isEmpty()) {
                var current = queue.removeFirst();
                size++;
                var x = current % width;
                var y = current / width;
                for (var ny = Math.max(0, y - 1); ny <= Math.min(height - 1, y + 1); ny++) {
                    for (var nx = Math.max(0, x - 1); nx <= Math.min(width - 1, x + 1); nx++) {
                        var next = ny * width + nx;
                        if (mask[next] && !seen[next]) {
                            seen[next] = true;
                            queue.addLast(next);
                        }
                    }
                }
            }
            if (size >= 4) {
                count++;
                pixels += size;
            }
        }
        return new Components(count, pixels);
    }

    private static String compartment(String marker) {
        return switch (marker) {
            case "er", "pr", "ki-67" -> "nuclear";
            case "her2" -> "membrane";
            case "pd-l1" -> "tumor-immune-region";
            default -> "generic-region";
        };
    }

    private record Components(int count, int pixels) {}

    public record Result(
            String marker,
            String compartment,
            int cellCount,
            Double meanNucleusAreaPx2,
            double dabAreaFraction,
            double meanDabOd,
            boolean researchEstimate) {}
}
