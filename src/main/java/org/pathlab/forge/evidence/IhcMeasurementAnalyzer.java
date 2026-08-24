package org.pathlab.forge.evidence;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic within-slide H/DAB descriptors; never emits clinical categories or scores. */
public final class IhcMeasurementAnalyzer {
    private static final Set<String> NUCLEAR = Set.of("er", "pr", "ki-67");
    private IhcMeasurementAnalyzer() { }

    public static Result analyze(BufferedImage image, String requestedMarker,
            List<ReviewedRegion> reviewedRegions, boolean controlsValidated) {
        var marker = requestedMarker == null ? "generic" : requestedMarker.toLowerCase();
        if (!Set.of("generic", "er", "pr", "ki-67", "her2", "pd-l1").contains(marker)) marker = "generic";
        var qc = BrightfieldStainQc.inspect(image, controlsValidated);
        var cells = OpticalDensityWatershed.segment(image).instances();
        if ("pd-l1".equals(marker)) {
            var approved = reviewedRegions == null ? List.<ReviewedRegion>of() : reviewedRegions.stream()
                    .filter(region -> Set.of("faculty-authored", "faculty-approved").contains(region.reviewSource()))
                    .filter(region -> Set.of("tumor", "immune").contains(region.kind()))
                    .toList();
            if (approved.isEmpty()) {
                return new Result(marker, "generic-fallback", "generic-region", qc.calibrationStatus(),
                        generic(image, cells, null), "COMPARTMENT_REVIEW_REQUIRED", qc.reasons(), 1 - qc.separationScore());
            }
            var compartments = new ArrayList<Compartment>();
            for (var region : approved) {
                compartments.add(new Compartment(region.id(), region.kind() + "-region",
                        generic(image, cells, region), region.reviewSource()));
            }
            return new Result(marker, "marker-aware", "tumor-immune-region", qc.calibrationStatus(),
                    Map.of("compartments", List.copyOf(compartments)), null, qc.reasons(), 1 - qc.separationScore());
        }
        if (NUCLEAR.contains(marker)) {
            return new Result(marker, "marker-aware", "nuclear", qc.calibrationStatus(),
                    nuclear(image, cells), null, qc.reasons(), 1 - qc.separationScore());
        }
        if ("her2".equals(marker)) {
            return new Result(marker, "marker-aware", "membrane", qc.calibrationStatus(),
                    membrane(image, cells), null, qc.reasons(), 1 - qc.separationScore());
        }
        return new Result("generic", "generic-descriptive", "generic-region", qc.calibrationStatus(),
                generic(image, cells, null), null, qc.reasons(), 1 - qc.separationScore());
    }

    private static Map<String, Object> nuclear(BufferedImage image,
            List<OpticalDensityWatershed.Instance> cells) {
        var low = 0;
        var moderate = 0;
        var strong = 0;
        var unstained = 0;
        double totalOd = 0;
        for (var cell : cells) {
            var od = meanDabOd(image, cell.rle(), null);
            totalOd += od;
            if (od >= 0.45) strong++;
            else if (od >= 0.25) moderate++;
            else if (od >= 0.10) low++;
            else unstained++;
        }
        var stained = low + moderate + strong;
        var values = new LinkedHashMap<String, Object>();
        values.put("totalNuclei", cells.size());
        values.put("dabAssociatedNuclei", stained);
        values.put("dabAssociatedFraction", cells.isEmpty() ? 0 : (double) stained / cells.size());
        values.put("meanNuclearDabOd", cells.isEmpty() ? 0 : totalOd / cells.size());
        values.put("intensityDistribution", Map.of(
                "unstained", unstained, "low", low, "moderate", moderate, "strong", strong));
        return Map.copyOf(values);
    }

    private static Map<String, Object> membrane(BufferedImage image,
            List<OpticalDensityWatershed.Instance> cells) {
        double completeness = 0;
        double continuity = 0;
        double intensity = 0;
        for (var cell : cells) {
            var ring = ringPixels(cell.rle(), image.getWidth(), image.getHeight());
            var brown = 0;
            for (var pixel : ring) if (isDab(image.getRGB(pixel % image.getWidth(), pixel / image.getWidth()))) brown++;
            var fraction = ring.isEmpty() ? 0 : (double) brown / ring.size();
            completeness += fraction;
            continuity += longestCircularRun(ring, image) / Math.max(1.0, ring.size());
            intensity += meanDabOd(image, ring.stream().flatMapToInt(pixel -> java.util.stream.IntStream.of(pixel, 1)).boxed().toList(), null);
        }
        var count = Math.max(1, cells.size());
        return Map.of(
                "evaluatedCellMasks", cells.size(),
                "meanMembraneCompleteness", completeness / count,
                "meanMembraneContinuity", continuity / count,
                "meanMembraneDabOd", intensity / count);
    }

    private static Map<String, Object> generic(BufferedImage image,
            List<OpticalDensityWatershed.Instance> cells, ReviewedRegion region) {
        var bounds = bounds(region, image.getWidth(), image.getHeight());
        var dab = 0;
        var tissue = 0;
        double od = 0;
        for (var y = bounds[1]; y < bounds[3]; y++) for (var x = bounds[0]; x < bounds[2]; x++) {
            var rgb = image.getRGB(x, y);
            var red = rgb >>> 16 & 0xff;
            var green = rgb >>> 8 & 0xff;
            var blue = rgb & 0xff;
            if ((red + green + blue) / 3.0 < 235) tissue++;
            if (isDab(rgb)) {
                dab++;
                od += dabOd(rgb);
            }
        }
        var associated = cells.stream().filter(cell -> inRegion(cell.centroidX(), cell.centroidY(), bounds))
                .filter(cell -> meanDabOd(image, cell.rle(), bounds) >= 0.10).count();
        return Map.of(
                "dabAreaFraction", tissue == 0 ? 0 : (double) dab / tissue,
                "meanDabOd", dab == 0 ? 0 : od / dab,
                "cellCount", cells.stream().filter(cell -> inRegion(cell.centroidX(), cell.centroidY(), bounds)).count(),
                "dabAssociatedCellCount", associated);
    }

    private static int[] bounds(ReviewedRegion region, int width, int height) {
        if (region == null) return new int[] {0, 0, width, height};
        var x0 = Math.max(0, Math.min(width, region.x()));
        var y0 = Math.max(0, Math.min(height, region.y()));
        var x1 = Math.max(x0, Math.min(width, region.x() + region.width()));
        var y1 = Math.max(y0, Math.min(height, region.y() + region.height()));
        return new int[] {x0, y0, x1, y1};
    }

    private static boolean inRegion(double x, double y, int[] bounds) {
        return x >= bounds[0] && x < bounds[2] && y >= bounds[1] && y < bounds[3];
    }

    private static double meanDabOd(BufferedImage image, List<Integer> rle, int[] bounds) {
        double total = 0;
        var count = 0;
        for (var index = 0; index + 1 < rle.size(); index += 2) {
            var start = rle.get(index);
            var length = rle.get(index + 1);
            for (var offset = 0; offset < length; offset++) {
                var pixel = start + offset;
                var x = pixel % image.getWidth();
                var y = pixel / image.getWidth();
                if (bounds != null && !inRegion(x, y, bounds)) continue;
                var rgb = image.getRGB(x, y);
                if (isDab(rgb)) {
                    total += dabOd(rgb);
                    count++;
                }
            }
        }
        return count == 0 ? 0 : total / count;
    }

    private static List<Integer> ringPixels(List<Integer> rle, int width, int height) {
        var mask = new java.util.HashSet<Integer>();
        for (var index = 0; index + 1 < rle.size(); index += 2) {
            for (var offset = 0; offset < rle.get(index + 1); offset++) mask.add(rle.get(index) + offset);
        }
        var ring = new java.util.TreeSet<Integer>();
        for (var pixel : mask) {
            var x = pixel % width;
            var y = pixel / width;
            for (var ny = Math.max(0, y - 1); ny <= Math.min(height - 1, y + 1); ny++) {
                for (var nx = Math.max(0, x - 1); nx <= Math.min(width - 1, x + 1); nx++) {
                    var candidate = ny * width + nx;
                    if (!mask.contains(candidate)) ring.add(candidate);
                }
            }
        }
        return List.copyOf(ring);
    }

    private static double longestCircularRun(List<Integer> ring, BufferedImage image) {
        var longest = 0;
        var current = 0;
        for (var pixel : ring) {
            if (isDab(image.getRGB(pixel % image.getWidth(), pixel / image.getWidth()))) {
                current++;
                longest = Math.max(longest, current);
            } else current = 0;
        }
        return longest;
    }

    private static boolean isDab(int rgb) {
        var red = rgb >>> 16 & 0xff;
        var green = rgb >>> 8 & 0xff;
        var blue = rgb & 0xff;
        return red - green >= 15 && green - blue >= 15 && red + green + blue < 660;
    }

    private static double dabOd(int rgb) {
        var red = rgb >>> 16 & 0xff;
        var green = rgb >>> 8 & 0xff;
        var blue = rgb & 0xff;
        var luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
        return -Math.log((luminance + 1) / 256.0);
    }

    public record ReviewedRegion(String id, String kind, String reviewSource,
            int x, int y, int width, int height) {
        public ReviewedRegion {
            if (id == null || !id.matches("[A-Za-z0-9._-]{1,120}")
                    || !Set.of("tumor", "immune", "analysis").contains(kind)
                    || !Set.of("faculty-authored", "faculty-approved", "model-suggested").contains(reviewSource)
                    || x < 0 || y < 0 || width < 1 || height < 1) {
                throw new IllegalArgumentException("Reviewed compartment region is invalid");
            }
        }
    }

    public record Result(String markerId, String analysisMode, String compartment,
            String calibrationStatus, Map<String, Object> measurements, String abstentionReason,
            List<String> qc, double uncertainty) { }
    public record Compartment(String regionId, String compartment, Map<String, Object> measurements,
            String reviewSource) { }
}
