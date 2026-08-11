package org.pathlab.forge.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.pathlab.forge.annotation.AnnotationRecord;
import org.pathlab.forge.annotation.AnnotationRepository;
import org.pathlab.forge.conversion.ConversionService;
import org.pathlab.forge.library.DatasetRepository;

public final class HeAnalysisService {
    public static final String SCHEMA = "pathlab-he-analysis/v1";
    public static final String ALGORITHM = "ruifrok-johnston-od-v1";
    private static final int MAX_SAMPLED_PIXELS = 1_048_576;
    private static final Set<String> CLOSED_TYPES = Set.of(
            "rectangle", "ellipse", "polygon", "freehand", "brush_add", "brush_subtract");
    private static final double[] H = {0.65, 0.70, 0.29};
    private static final double[] E = {0.2159, 0.8012, 0.5581};
    private final DatasetRepository datasets;
    private final AnnotationRepository annotations;
    private final ConversionService conversion;
    private final Path managedRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public HeAnalysisService(
            DatasetRepository datasets,
            AnnotationRepository annotations,
            ConversionService conversion,
            Path managedRoot) {
        this.datasets = datasets;
        this.annotations = annotations;
        this.conversion = conversion;
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
    }

    public Result analyze(
            String datasetId, String annotationId, double hematoxylinThreshold, double eosinThreshold)
            throws IOException {
        if (!finiteThreshold(hematoxylinThreshold) || !finiteThreshold(eosinThreshold)) {
            throw new IllegalArgumentException("H&E thresholds must be finite values from 0 to 3 OD");
        }
        var dataset = datasets.find(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset was not found"));
        var annotation = annotations.list(datasetId).stream()
                .filter(item -> item.id().equals(annotationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Annotation was not found"));
        if (!CLOSED_TYPES.contains(annotation.type())) {
            throw new IllegalArgumentException("H&E analysis requires a closed annotation ROI");
        }
        var points = points(annotation.geometry());
        var bounds = bounds(points, dataset.width(), dataset.height());
        if ((long) bounds.width() * bounds.height() > 4_194_304) {
            throw new IllegalArgumentException(
                    "H&E ROI exceeds 4,194,304 decoded pixels; draw a smaller ROI");
        }
        var region = conversion.readRgbRegion(
                datasetId, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        var rgb = region.interleavedRgb();
        var stride = Math.max(1, (int) Math.ceil(Math.sqrt(
                (double) bounds.width() * bounds.height() / MAX_SAMPLED_PIXELS)));
        var maxSamples = Math.toIntExact(((long) bounds.width() + stride - 1) / stride
                * (((long) bounds.height() + stride - 1) / stride));
        var hematoxylin = new double[maxSamples];
        var eosin = new double[maxSamples];
        var inverse = inverse(stainMatrix());
        int count = 0;
        double hSum = 0;
        double eSum = 0;
        int hAbove = 0;
        int eAbove = 0;
        for (var y = 0; y < bounds.height(); y += stride) {
            for (var x = 0; x < bounds.width(); x += stride) {
                var sourceX = bounds.x() + x + 0.5;
                var sourceY = bounds.y() + y + 0.5;
                if (!inside(annotation.type(), points, sourceX, sourceY)) {
                    continue;
                }
                var index = (y * bounds.width() + x) * 3;
                var redOd = opticalDensity(rgb[index] & 0xff);
                var greenOd = opticalDensity(rgb[index + 1] & 0xff);
                var blueOd = opticalDensity(rgb[index + 2] & 0xff);
                var concentrations = concentrations(redOd, greenOd, blueOd, inverse);
                var h = concentrations[0];
                var e = concentrations[1];
                hematoxylin[count] = h;
                eosin[count] = e;
                hSum += h;
                eSum += e;
                if (h >= hematoxylinThreshold) hAbove++;
                if (e >= eosinThreshold) eAbove++;
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("H&E ROI contains no sampled pixels");
        }
        Arrays.sort(hematoxylin, 0, count);
        Arrays.sort(eosin, 0, count);
        var result = new Result(
                SCHEMA,
                true,
                ALGORITHM,
                "qupath-he-default",
                "f736e1ee0a29bc2658c7621df6d28ba9202bd04e",
                H.clone(),
                E.clone(),
                new int[] {255, 255, 255},
                hematoxylinThreshold,
                eosinThreshold,
                count,
                stride,
                new StainStats(hSum / count, percentile(hematoxylin, count, 0.5),
                        percentile(hematoxylin, count, 0.9), (double) hAbove / count),
                new StainStats(eSum / count, percentile(eosin, count, 0.5),
                        percentile(eosin, count, 0.9), (double) eAbove / count),
                dataset.sourceFingerprint(),
                dataset.selectedSeries(),
                sha256(annotation.geometry()),
                System.currentTimeMillis());
        persist(datasetId, annotationId, result);
        return result;
    }

    private void persist(String datasetId, String annotationId, Result result) throws IOException {
        var directory = managedRoot.resolve(datasetId).resolve("analysis").resolve(annotationId).normalize();
        if (!directory.startsWith(managedRoot.resolve(datasetId).normalize())) {
            throw new IOException("Analysis result path escaped managed storage");
        }
        Files.createDirectories(directory);
        var target = directory.resolve("he-v1.json");
        var partial = directory.resolve("he-v1.json.partial");
        mapper.writeValue(partial.toFile(), result);
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean finiteThreshold(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 3;
    }

    private static double opticalDensity(int value) {
        return -Math.log((value + 1.0) / 256.0);
    }

    static double[] concentrations(int red, int green, int blue) {
        return concentrations(
                opticalDensity(red), opticalDensity(green), opticalDensity(blue),
                inverse(stainMatrix()));
    }

    private static double[] concentrations(
            double redOd, double greenOd, double blueOd, double[][] inverse) {
        return new double[] {
            Math.max(0, inverse[0][0] * redOd + inverse[0][1] * greenOd + inverse[0][2] * blueOd),
            Math.max(0, inverse[1][0] * redOd + inverse[1][1] * greenOd + inverse[1][2] * blueOd)
        };
    }

    private static double percentile(double[] values, int count, double fraction) {
        return values[Math.min(count - 1, Math.max(0, (int) Math.floor((count - 1) * fraction)))];
    }

    private static List<Point> points(String geometry) {
        return Arrays.stream(geometry.split(";"))
                .map(point -> point.split(",", -1))
                .map(parts -> new Point(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])))
                .toList();
    }

    private static Bounds bounds(List<Point> points, int width, int height) {
        var minX = Math.max(0, (int) Math.floor(points.stream().mapToDouble(Point::x).min().orElseThrow()));
        var minY = Math.max(0, (int) Math.floor(points.stream().mapToDouble(Point::y).min().orElseThrow()));
        var maxX = Math.min(width, (int) Math.ceil(points.stream().mapToDouble(Point::x).max().orElseThrow()));
        var maxY = Math.min(height, (int) Math.ceil(points.stream().mapToDouble(Point::y).max().orElseThrow()));
        if (maxX <= minX || maxY <= minY) {
            throw new IllegalArgumentException("H&E ROI has no area");
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }

    private static boolean inside(String type, List<Point> points, double x, double y) {
        if ("rectangle".equals(type) || "brush_add".equals(type) || "brush_subtract".equals(type)) {
            return true;
        }
        if ("ellipse".equals(type) && points.size() >= 2) {
            var centerX = (points.get(0).x() + points.get(1).x()) / 2;
            var centerY = (points.get(0).y() + points.get(1).y()) / 2;
            var radiusX = Math.abs(points.get(1).x() - points.get(0).x()) / 2;
            var radiusY = Math.abs(points.get(1).y() - points.get(0).y()) / 2;
            return radiusX > 0 && radiusY > 0
                    && Math.pow((x - centerX) / radiusX, 2) + Math.pow((y - centerY) / radiusY, 2) <= 1;
        }
        var contained = false;
        for (int current = 0, previous = points.size() - 1; current < points.size(); previous = current++) {
            var a = points.get(current);
            var b = points.get(previous);
            if ((a.y() > y) != (b.y() > y)
                    && x < (b.x() - a.x()) * (y - a.y()) / (b.y() - a.y()) + a.x()) {
                contained = !contained;
            }
        }
        return contained;
    }

    private static double[][] stainMatrix() {
        var h = normalize(H);
        var e = normalize(E);
        var residual = normalize(new double[] {
            h[1] * e[2] - h[2] * e[1],
            h[2] * e[0] - h[0] * e[2],
            h[0] * e[1] - h[1] * e[0]
        });
        return new double[][] {
            {h[0], e[0], residual[0]},
            {h[1], e[1], residual[1]},
            {h[2], e[2], residual[2]}
        };
    }

    private static double[] normalize(double[] vector) {
        var length = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        return new double[] {vector[0] / length, vector[1] / length, vector[2] / length};
    }

    private static double[][] inverse(double[][] matrix) {
        var determinant = matrix[0][0] * (matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1])
                - matrix[0][1] * (matrix[1][0] * matrix[2][2] - matrix[1][2] * matrix[2][0])
                + matrix[0][2] * (matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0]);
        if (Math.abs(determinant) < 1e-12) throw new IllegalStateException("Stain vectors are singular");
        return new double[][] {
            {(matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1]) / determinant,
                    (matrix[0][2] * matrix[2][1] - matrix[0][1] * matrix[2][2]) / determinant,
                    (matrix[0][1] * matrix[1][2] - matrix[0][2] * matrix[1][1]) / determinant},
            {(matrix[1][2] * matrix[2][0] - matrix[1][0] * matrix[2][2]) / determinant,
                    (matrix[0][0] * matrix[2][2] - matrix[0][2] * matrix[2][0]) / determinant,
                    (matrix[0][2] * matrix[1][0] - matrix[0][0] * matrix[1][2]) / determinant},
            {(matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0]) / determinant,
                    (matrix[0][1] * matrix[2][0] - matrix[0][0] * matrix[2][1]) / determinant,
                    (matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]) / determinant}
        };
    }

    private static String sha256(String value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IOException("Analysis provenance hash could not be created", error);
        }
    }

    public record Result(
            String schema,
            boolean researchOnly,
            String algorithm,
            String preset,
            String presetSourceCommit,
            double[] hematoxylinVector,
            double[] eosinVector,
            int[] background,
            double hematoxylinThreshold,
            double eosinThreshold,
            int sampledPixels,
            int samplingStride,
            StainStats hematoxylin,
            StainStats eosin,
            String sourceFingerprint,
            int seriesIndex,
            String geometrySha256,
            long createdAt) {}

    public record StainStats(double meanOd, double medianOd, double p90Od, double fractionAboveThreshold) {}
    private record Point(double x, double y) {}
    private record Bounds(int x, int y, int width, int height) {}
}
