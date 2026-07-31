package org.pathlab.forge.derivative;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

final class AdaptiveJpegQualitySelector {
    static final double MINIMUM_SSIM = 0.970;
    static final double MAXIMUM_MEAN_DELTA_E00 = 2.5;
    static final double MINIMUM_EDGE_DETAIL_RETENTION = 0.90;
    private static final int ROI_COLUMNS = 8;
    private static final int ROI_ROWS = 8;
    private static final int ROI_SIZE = 256;
    private static final int WINDOW = 8;
    static final List<Integer> QUALITIES = List.of(65, 70, 75, 80);

    private AdaptiveJpegQualitySelector() {}

    static List<Roi> planNativeRois(
            Path overviewPath, int fullWidth, int fullHeight) throws IOException {
        var overview = ImageIO.read(overviewPath.toFile());
        if (overview == null || fullWidth < ROI_SIZE || fullHeight < ROI_SIZE) {
            throw new IOException("Native DZI quality ROI overview could not be decoded");
        }
        var candidates = new ArrayList<RoiCandidate>();
        for (var row = 0; row < 8; row++) {
            var top = row * overview.getHeight() / 8;
            var bottom = Math.max(top + 1, (row + 1) * overview.getHeight() / 8);
            for (var column = 0; column < 8; column++) {
                var left = column * overview.getWidth() / 8;
                var right = Math.max(left + 1, (column + 1) * overview.getWidth() / 8);
                var mean = 0.0;
                var squared = 0.0;
                var count = 0;
                for (var y = top; y < bottom; y++) {
                    for (var x = left; x < right; x++) {
                        var value = luminance(overview.getRGB(x, y));
                        mean += value;
                        squared += value * value;
                        count++;
                    }
                }
                mean /= count;
                var variance = Math.max(0.0, squared / count - mean * mean);
                var centerX = ((left + right) * (long) fullWidth)
                        / (2L * overview.getWidth());
                var centerY = ((top + bottom) * (long) fullHeight)
                        / (2L * overview.getHeight());
                candidates.add(new RoiCandidate(
                        centeredRoi(centerX, centerY, fullWidth, fullHeight),
                        mean,
                        variance));
            }
        }

        var selected = new LinkedHashMap<String, Roi>();
        addRanked(
                selected,
                candidates.stream()
                        .sorted(Comparator.comparingDouble(RoiCandidate::mean))
                        .toList(),
                16);
        addRanked(
                selected,
                candidates.stream()
                        .sorted(Comparator.comparingDouble(RoiCandidate::mean).reversed())
                        .toList(),
                32);
        addRanked(
                selected,
                candidates.stream()
                        .sorted(Comparator.comparingDouble(RoiCandidate::variance).reversed())
                        .toList(),
                48);
        for (var index = 0; index < 32 && selected.size() < 64; index++) {
            var seamX = Math.max(
                    512L,
                    Math.min(
                            fullWidth - 1L,
                            Math.round((index + 1.0) * fullWidth / 17.0 / 512.0) * 512L));
            var centerY = Math.round(((index % 8) + 0.5) * fullHeight / 8.0);
            add(selected, centeredRoi(seamX, centerY, fullWidth, fullHeight));
        }
        addRanked(selected, candidates, 64);
        if (selected.size() < 64) {
            throw new IOException("Could not derive 64 distinct native quality ROIs");
        }
        return selected.values().stream().limit(64).toList();
    }

    private static void addRanked(
            Map<String, Roi> selected, List<RoiCandidate> candidates, int targetSize) {
        for (var candidate : candidates) {
            if (selected.size() >= targetSize) {
                return;
            }
            add(selected, candidate.roi());
        }
    }

    private static void add(Map<String, Roi> selected, Roi roi) {
        selected.putIfAbsent(roi.x() + ":" + roi.y(), roi);
    }

    private static Roi centeredRoi(
            long centerX, long centerY, int fullWidth, int fullHeight) {
        var x = Math.toIntExact(
                Math.max(0, Math.min(fullWidth - ROI_SIZE, centerX - ROI_SIZE / 2L)));
        var y = Math.toIntExact(
                Math.max(0, Math.min(fullHeight - ROI_SIZE, centerY - ROI_SIZE / 2L)));
        return new Roi(x, y, ROI_SIZE, ROI_SIZE);
    }

    static Selection select(Path boundedProbe) throws IOException {
        var image = ImageIO.read(boundedProbe.toFile());
        if (image == null || image.getWidth() < ROI_COLUMNS || image.getHeight() < ROI_ROWS) {
            throw new IOException("Bounded DZI quality probe could not be decoded");
        }
        var regions = regions(image);
        Selection last = null;
        for (var quality : QUALITIES) {
            var candidates = regions.stream()
                    .map(region -> {
                        try {
                            return decode(encode(region, quality));
                        } catch (IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    })
                    .toList();
            last = evaluate(quality, regions, candidates);
            if (last.minimumWindowedSsim() >= MINIMUM_SSIM
                    && last.meanDeltaE00() <= MAXIMUM_MEAN_DELTA_E00
                    && last.minimumEdgeDetailRetention() >= MINIMUM_EDGE_DETAIL_RETENTION) {
                return last;
            }
        }
        return requirePassing(last);
    }

    static Selection select(
            Path boundedProbe, Map<Integer, Path> encodedCandidates) throws IOException {
        var image = ImageIO.read(boundedProbe.toFile());
        if (image == null || image.getWidth() < ROI_COLUMNS || image.getHeight() < ROI_ROWS) {
            throw new IOException("Bounded DZI quality probe could not be decoded");
        }
        var regions = regions(image);
        Selection last = null;
        for (var quality : QUALITIES) {
            var path = encodedCandidates.get(quality);
            var candidate = path == null ? null : ImageIO.read(path.toFile());
            if (candidate == null
                    || candidate.getWidth() != image.getWidth()
                    || candidate.getHeight() != image.getHeight()) {
                throw new IOException("DZI quality candidate could not be decoded");
            }
            last = evaluate(quality, regions, regions(candidate));
            if (last.minimumWindowedSsim() >= MINIMUM_SSIM
                    && last.meanDeltaE00() <= MAXIMUM_MEAN_DELTA_E00
                    && last.minimumEdgeDetailRetention() >= MINIMUM_EDGE_DETAIL_RETENTION) {
                return last;
            }
        }
        return requirePassing(last);
    }

    static Selection selectCandidate(
            Path boundedProbe, Path encodedCandidate, int quality, String encoderProfile)
            throws IOException {
        var reference = ImageIO.read(boundedProbe.toFile());
        var candidate = ImageIO.read(encodedCandidate.toFile());
        if (reference == null
                || candidate == null
                || candidate.getWidth() != reference.getWidth()
                || candidate.getHeight() != reference.getHeight()) {
            throw new IOException("DZI quality candidate could not be decoded");
        }
        var selection = evaluate(quality, regions(reference), regions(candidate))
                .withEncoderProfile(encoderProfile);
        if (selection.minimumWindowedSsim() >= MINIMUM_SSIM
                && selection.meanDeltaE00() <= MAXIMUM_MEAN_DELTA_E00
                && selection.minimumEdgeDetailRetention() >= MINIMUM_EDGE_DETAIL_RETENTION) {
            return selection;
        }
        return requirePassing(selection);
    }

    private static Selection evaluate(
            int quality, List<BufferedImage> references, List<BufferedImage> candidates) {
        var minimumSsim = 1.0;
        var maximumMeanDeltaE = 0.0;
        var minimumEdgeDetailRetention = 1.0;
        for (var index = 0; index < references.size(); index++) {
            minimumSsim = Math.min(
                    minimumSsim,
                    windowedSsim(references.get(index), candidates.get(index)));
            maximumMeanDeltaE = Math.max(
                    maximumMeanDeltaE,
                    meanDeltaE00(references.get(index), candidates.get(index)));
            minimumEdgeDetailRetention = Math.min(
                    minimumEdgeDetailRetention,
                    edgeDetailRetention(references.get(index), candidates.get(index)));
        }
        return new Selection(
                quality,
                minimumSsim,
                maximumMeanDeltaE,
                minimumEdgeDetailRetention,
                "compact-baseline");
    }

    private static Selection requirePassing(Selection last) throws IOException {
        if (last == null) {
            throw new IOException("DZI quality candidates are unavailable");
        }
        throw new IOException(
                ("DZI JPEG quality gate failed at Q%d: minimum windowed SSIM %.6f; "
                                + "maximum ROI mean Delta E00 %.6f; minimum edge retention %.6f")
                        .formatted(
                                last.quality(),
                                last.minimumWindowedSsim(),
                                last.meanDeltaE00(),
                                last.minimumEdgeDetailRetention()));
    }

    private static List<BufferedImage> regions(BufferedImage image) {
        var result = new ArrayList<BufferedImage>(ROI_COLUMNS * ROI_ROWS);
        for (var row = 0; row < ROI_ROWS; row++) {
            var top = row * image.getHeight() / ROI_ROWS;
            var bottom = (row + 1) * image.getHeight() / ROI_ROWS;
            for (var column = 0; column < ROI_COLUMNS; column++) {
                var left = column * image.getWidth() / ROI_COLUMNS;
                var right = (column + 1) * image.getWidth() / ROI_COLUMNS;
                var width = Math.min(ROI_SIZE, right - left);
                var height = Math.min(ROI_SIZE, bottom - top);
                var x = left + (right - left - width) / 2;
                var y = top + (bottom - top - height) / 2;
                result.add(image.getSubimage(x, y, width, height));
            }
        }
        return List.copyOf(result);
    }

    private static byte[] encode(BufferedImage image, int quality) throws IOException {
        var output = new ByteArrayOutputStream();
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer is unavailable");
        }
        var writer = writers.next();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality / 100.0f);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        var decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (decoded == null) {
            throw new IOException("JPEG quality candidate could not be decoded");
        }
        return decoded;
    }

    static double windowedSsim(BufferedImage reference, BufferedImage candidate) {
        requireSameGeometry(reference, candidate);
        var scores = new ArrayList<Double>();
        for (var y = 0; y < reference.getHeight(); y += WINDOW) {
            for (var x = 0; x < reference.getWidth(); x += WINDOW) {
                var width = Math.min(WINDOW, reference.getWidth() - x);
                var height = Math.min(WINDOW, reference.getHeight() - y);
                scores.add(windowSsim(reference, candidate, x, y, width, height));
            }
        }
        return scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double windowSsim(
            BufferedImage reference,
            BufferedImage candidate,
            int x,
            int y,
            int width,
            int height) {
        var count = width * height;
        var referenceMean = 0.0;
        var candidateMean = 0.0;
        for (var row = y; row < y + height; row++) {
            for (var column = x; column < x + width; column++) {
                referenceMean += luminance(reference.getRGB(column, row));
                candidateMean += luminance(candidate.getRGB(column, row));
            }
        }
        referenceMean /= count;
        candidateMean /= count;
        var referenceVariance = 0.0;
        var candidateVariance = 0.0;
        var covariance = 0.0;
        for (var row = y; row < y + height; row++) {
            for (var column = x; column < x + width; column++) {
                var left = luminance(reference.getRGB(column, row)) - referenceMean;
                var right = luminance(candidate.getRGB(column, row)) - candidateMean;
                referenceVariance += left * left;
                candidateVariance += right * right;
                covariance += left * right;
            }
        }
        var divisor = Math.max(1, count - 1);
        referenceVariance /= divisor;
        candidateVariance /= divisor;
        covariance /= divisor;
        var c1 = Math.pow(0.01 * 255, 2);
        var c2 = Math.pow(0.03 * 255, 2);
        return ((2 * referenceMean * candidateMean + c1) * (2 * covariance + c2))
                / ((referenceMean * referenceMean + candidateMean * candidateMean + c1)
                        * (referenceVariance + candidateVariance + c2));
    }

    static double meanDeltaE00(BufferedImage reference, BufferedImage candidate) {
        requireSameGeometry(reference, candidate);
        var total = 0.0;
        for (var y = 0; y < reference.getHeight(); y++) {
            for (var x = 0; x < reference.getWidth(); x++) {
                total += deltaE00(lab(reference.getRGB(x, y)), lab(candidate.getRGB(x, y)));
            }
        }
        return total / ((long) reference.getWidth() * reference.getHeight());
    }

    static double edgeDetailRetention(BufferedImage reference, BufferedImage candidate) {
        requireSameGeometry(reference, candidate);
        var referenceEnergy = gradientEnergy(reference);
        if (referenceEnergy < 1e-6) {
            return 1.0;
        }
        return Math.min(1.0, gradientEnergy(candidate) / referenceEnergy);
    }

    private static double gradientEnergy(BufferedImage image) {
        var total = 0.0;
        for (var y = 1; y < image.getHeight() - 1; y++) {
            for (var x = 1; x < image.getWidth() - 1; x++) {
                var gx = luminance(image.getRGB(x + 1, y))
                        - luminance(image.getRGB(x - 1, y));
                var gy = luminance(image.getRGB(x, y + 1))
                        - luminance(image.getRGB(x, y - 1));
                total += Math.hypot(gx, gy);
            }
        }
        return total;
    }

    private static void requireSameGeometry(BufferedImage left, BufferedImage right) {
        if (left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) {
            throw new IllegalArgumentException("Quality images must have identical geometry");
        }
    }

    private static double luminance(int rgb) {
        return 0.2126 * ((rgb >>> 16) & 0xff)
                + 0.7152 * ((rgb >>> 8) & 0xff)
                + 0.0722 * (rgb & 0xff);
    }

    private static Lab lab(int rgb) {
        var red = linear(((rgb >>> 16) & 0xff) / 255.0);
        var green = linear(((rgb >>> 8) & 0xff) / 255.0);
        var blue = linear((rgb & 0xff) / 255.0);
        var x = (0.4124564 * red + 0.3575761 * green + 0.1804375 * blue) / 0.95047;
        var y = (0.2126729 * red + 0.7151522 * green + 0.0721750 * blue);
        var z = (0.0193339 * red + 0.1191920 * green + 0.9503041 * blue) / 1.08883;
        var fx = pivot(x);
        var fy = pivot(y);
        var fz = pivot(z);
        return new Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz));
    }

    private static double linear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double pivot(double value) {
        return value > 216.0 / 24_389
                ? Math.cbrt(value)
                : (24_389.0 / 27 * value + 16) / 116;
    }

    // Sharma, Wu and Dalal CIEDE2000 with unit weighting factors.
    private static double deltaE00(Lab first, Lab second) {
        var c1 = Math.hypot(first.a(), first.b());
        var c2 = Math.hypot(second.a(), second.b());
        var meanC = (c1 + c2) / 2;
        var g = 0.5 * (1 - Math.sqrt(Math.pow(meanC, 7) / (Math.pow(meanC, 7) + Math.pow(25, 7))));
        var a1 = (1 + g) * first.a();
        var a2 = (1 + g) * second.a();
        var adjustedC1 = Math.hypot(a1, first.b());
        var adjustedC2 = Math.hypot(a2, second.b());
        var h1 = hue(a1, first.b());
        var h2 = hue(a2, second.b());
        var deltaL = second.l() - first.l();
        var deltaC = adjustedC2 - adjustedC1;
        var deltaHAngle = h2 - h1;
        if (adjustedC1 * adjustedC2 != 0) {
            if (deltaHAngle > 180) {
                deltaHAngle -= 360;
            } else if (deltaHAngle < -180) {
                deltaHAngle += 360;
            }
        } else {
            deltaHAngle = 0;
        }
        var deltaH = 2 * Math.sqrt(adjustedC1 * adjustedC2)
                * Math.sin(Math.toRadians(deltaHAngle / 2));
        var meanL = (first.l() + second.l()) / 2;
        var meanAdjustedC = (adjustedC1 + adjustedC2) / 2;
        double meanH;
        if (adjustedC1 * adjustedC2 == 0) {
            meanH = h1 + h2;
        } else if (Math.abs(h1 - h2) <= 180) {
            meanH = (h1 + h2) / 2;
        } else if (h1 + h2 < 360) {
            meanH = (h1 + h2 + 360) / 2;
        } else {
            meanH = (h1 + h2 - 360) / 2;
        }
        var t = 1
                - 0.17 * Math.cos(Math.toRadians(meanH - 30))
                + 0.24 * Math.cos(Math.toRadians(2 * meanH))
                + 0.32 * Math.cos(Math.toRadians(3 * meanH + 6))
                - 0.20 * Math.cos(Math.toRadians(4 * meanH - 63));
        var deltaTheta = 30 * Math.exp(-Math.pow((meanH - 275) / 25, 2));
        var rc = 2 * Math.sqrt(
                Math.pow(meanAdjustedC, 7)
                        / (Math.pow(meanAdjustedC, 7) + Math.pow(25, 7)));
        var sl = 1 + 0.015 * Math.pow(meanL - 50, 2)
                / Math.sqrt(20 + Math.pow(meanL - 50, 2));
        var sc = 1 + 0.045 * meanAdjustedC;
        var sh = 1 + 0.015 * meanAdjustedC * t;
        var rt = -Math.sin(Math.toRadians(2 * deltaTheta)) * rc;
        var l = deltaL / sl;
        var c = deltaC / sc;
        var h = deltaH / sh;
        return Math.sqrt(l * l + c * c + h * h + rt * c * h);
    }

    private static double hue(double a, double b) {
        var degrees = Math.toDegrees(Math.atan2(b, a));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    record Selection(
            int quality,
            double minimumWindowedSsim,
            double meanDeltaE00,
            double minimumEdgeDetailRetention,
            String encoderProfile) {
        Selection(
                int quality,
                double minimumWindowedSsim,
                double meanDeltaE00,
                double minimumEdgeDetailRetention) {
            this(
                    quality,
                    minimumWindowedSsim,
                    meanDeltaE00,
                    minimumEdgeDetailRetention,
                    "compact-baseline");
        }

        Selection withEncoderProfile(String profile) {
            return new Selection(
                    quality,
                    minimumWindowedSsim,
                    meanDeltaE00,
                    minimumEdgeDetailRetention,
                    profile);
        }
    }

    record Roi(int x, int y, int width, int height) {}

    private record RoiCandidate(Roi roi, double mean, double variance) {}

    private record Lab(double l, double a, double b) {}
}
