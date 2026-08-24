package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Evaluates the deterministic cell-instance fallback against a frozen held-out cohort. */
public final class CellInstanceQualificationEvaluator {
    public static final String SCHEMA = "pathlab.cell-instance-metrics/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ORGANS = Set.of("lung", "kidney", "breast", "prostate");

    private CellInstanceQualificationEvaluator() { }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "Usage: <cohort.json> <sha256> <output.json>");
        var output = Path.of(args[2]).toAbsolutePath().normalize();
        var metrics = evaluate(Path.of(args[0]), args[1]);
        Files.createDirectories(output.getParent());
        var partial = output.resolveSibling(output.getFileName() + ".partial");
        JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), metrics);
        try {
            Files.move(partial, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(partial, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static ObjectNode evaluate(Path manifestPath, String expectedSha256) throws IOException {
        var normalizedManifest = manifestPath.toAbsolutePath().normalize();
        require(Files.isRegularFile(normalizedManifest)
                        && expectedSha256.matches("[a-f0-9]{64}")
                        && expectedSha256.equals(sha256(normalizedManifest)),
                "Cell qualification cohort checksum does not match");
        var cohort = JSON.readTree(normalizedManifest.toFile());
        require("pathlab.cell-qualification-cohort/1".equals(cohort.path("schema").asText())
                        && cohort.path("sampleCount").asInt(-1) == cohort.path("samples").size()
                        && cohort.path("samples").isArray() && cohort.path("samples").size() >= 4,
                "Cell qualification cohort contract is invalid");
        var root = normalizedManifest.getParent();
        var started = System.nanoTime();
        long peakHeapBytes = usedHeapBytes();
        var rows = new ArrayList<SampleMetrics>();
        var failures = 0;
        var repeatable = true;
        var rightsAndIntegrity = true;
        var observedOrgans = new java.util.HashSet<String>();
        for (var sample : cohort.path("samples")) {
            try {
                var organ = requiredText(sample, "organ");
                require(ORGANS.contains(organ)
                                && "qualification-held-out-test".equals(requiredText(sample, "split"))
                                && !sample.path("patientOverlapWithTraining").asBoolean(true)
                                && "CC-BY-NC-SA-4.0".equals(requiredText(sample, "license"))
                                && "private-research-restricted".equals(requiredText(sample, "permittedUse")),
                        "Cell qualification sample rights or split is invalid");
                var imagePath = relativePath(root, requiredText(sample, "imagePath"));
                var annotationPath = relativePath(root, requiredText(sample, "annotationPath"));
                require(requiredText(sample, "imageSha256").equals(sha256(imagePath))
                                && requiredText(sample, "annotationSha256").equals(sha256(annotationPath)),
                        "Cell qualification sample checksum changed");
                var image = ImageIO.read(imagePath.toFile());
                require(image != null && image.getWidth() == sample.path("width").asInt()
                                && image.getHeight() == sample.path("height").asInt()
                                && (long) image.getWidth() * image.getHeight() <= 4_194_304,
                        "Cell qualification image geometry is invalid");
                var truth = groundTruth(annotationPath, image.getWidth(), image.getHeight());
                require(!truth.isEmpty(), "Cell qualification annotation contains no instances");
                var first = OpticalDensityWatershed.segment(image);
                var second = OpticalDensityWatershed.segment(image);
                repeatable &= first.equals(second);
                rows.add(compare(organ, truth, predicted(first.instances(), image.getWidth(), image.getHeight())));
                observedOrgans.add(organ);
                peakHeapBytes = Math.max(peakHeapBytes, usedHeapBytes());
            } catch (RuntimeException | IOException failure) {
                failures++;
                rightsAndIntegrity = false;
            }
        }
        require(!rows.isEmpty(), "No cell qualification region was evaluable");
        var elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
        var gates = cohort.path("gates");
        var macroPq = rows.stream().mapToDouble(SampleMetrics::pq).average().orElseThrow();
        var instanceDice = rows.stream().mapToDouble(SampleMetrics::dice).average().orElseThrow();
        var countError = rows.stream().mapToDouble(SampleMetrics::countError).average().orElseThrow();
        var morphometry = median(rows.stream().map(SampleMetrics::morphometryBias).sorted().toList());
        var failedRegionRate = (double) failures / cohort.path("samples").size();
        var crossTissue = observedOrgans.equals(ORGANS);
        var peakHeapMiB = peakHeapBytes / 1_048_576.0;
        var resourceCompliant = elapsedSeconds <= 120.0 && peakHeapMiB <= 1024.0;

        var result = JSON.createObjectNode();
        result.put("schema", SCHEMA);
        result.put("cohortManifestSha256", expectedSha256);
        result.put("sampleCount", cohort.path("samples").size());
        result.put("evaluatedSampleCount", rows.size());
        result.put("macroPq", macroPq);
        result.put("instanceDice", instanceDice);
        result.put("countError", countError);
        result.put("morphometryBias", morphometry);
        result.put("failedRegionRate", failedRegionRate);
        result.put("deterministicRepeat", repeatable);
        result.put("crossTissuePerformance", crossTissue);
        result.put("rightsAndIntegrityPassed", rightsAndIntegrity);
        result.put("resourceCompliant", resourceCompliant);
        result.put("elapsedSeconds", elapsedSeconds);
        result.put("peakHeapMiB", peakHeapMiB);
        copyGate(gates, result, "minimumMacroPq");
        copyGate(gates, result, "minimumInstanceDice");
        copyGate(gates, result, "maximumCountError");
        copyGate(gates, result, "maximumMorphometryBias");
        copyGate(gates, result, "maximumFailedRegionRate");
        result.put("sourceIntegrity", cohort.path("source").path("integrity").asText());
        result.put("upstreamChecksumAvailable",
                cohort.path("source").path("upstreamChecksumAvailable").asBoolean(false));
        var perOrgan = result.putObject("perOrgan");
        for (var organ : ORGANS.stream().sorted().toList()) {
            var organRows = rows.stream().filter(row -> organ.equals(row.organ())).toList();
            var item = perOrgan.putObject(organ);
            item.put("sampleCount", organRows.size());
            item.put("macroPq", organRows.stream().mapToDouble(SampleMetrics::pq).average().orElse(Double.NaN));
            item.put("instanceDice", organRows.stream().mapToDouble(SampleMetrics::dice).average().orElse(Double.NaN));
            item.put("countError", organRows.stream().mapToDouble(SampleMetrics::countError).average().orElse(Double.NaN));
        }
        var reasons = result.putArray("notEvaluableReasons");
        if (!rightsAndIntegrity) reasons.add("CELL_COHORT_RIGHTS_OR_INTEGRITY_FAILED");
        if (!crossTissue) reasons.add("CELL_CROSS_TISSUE_COVERAGE_INCOMPLETE");
        if (!resourceCompliant) reasons.add("CELL_RESOURCE_ENVELOPE_EXCEEDED");
        return result;
    }

    private static SampleMetrics compare(String organ, List<Mask> truth, List<Mask> predicted) {
        var pairs = new ArrayList<Pair>();
        for (var truthIndex = 0; truthIndex < truth.size(); truthIndex++) {
            for (var predictedIndex = 0; predictedIndex < predicted.size(); predictedIndex++) {
                var intersection = intersection(truth.get(truthIndex), predicted.get(predictedIndex));
                if (intersection == 0) continue;
                var union = truth.get(truthIndex).area() + predicted.get(predictedIndex).area() - intersection;
                var iou = (double) intersection / union;
                if (iou >= 0.5) pairs.add(new Pair(truthIndex, predictedIndex, intersection, iou));
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::iou).reversed()
                .thenComparingInt(Pair::truthIndex).thenComparingInt(Pair::predictedIndex));
        var usedTruth = new boolean[truth.size()];
        var usedPredicted = new boolean[predicted.size()];
        var matches = new ArrayList<Pair>();
        for (var pair : pairs) {
            if (!usedTruth[pair.truthIndex()] && !usedPredicted[pair.predictedIndex()]) {
                usedTruth[pair.truthIndex()] = true;
                usedPredicted[pair.predictedIndex()] = true;
                matches.add(pair);
            }
        }
        var falsePositive = predicted.size() - matches.size();
        var falseNegative = truth.size() - matches.size();
        var pqDenominator = matches.size() + 0.5 * falsePositive + 0.5 * falseNegative;
        var pq = pqDenominator == 0 ? 0 : matches.stream().mapToDouble(Pair::iou).sum() / pqDenominator;
        var diceDenominator = Math.max(truth.size(), predicted.size());
        var dice = diceDenominator == 0 ? 0 : matches.stream().mapToDouble(pair -> {
            var truthMask = truth.get(pair.truthIndex());
            var predictedMask = predicted.get(pair.predictedIndex());
            return 2.0 * pair.intersection() / (truthMask.area() + predictedMask.area());
        }).sum() / diceDenominator;
        var biases = matches.stream().mapToDouble(pair -> {
            var truthMask = truth.get(pair.truthIndex());
            var predictedMask = predicted.get(pair.predictedIndex());
            var areaBias = Math.abs(predictedMask.area() - truthMask.area()) / (double) truthMask.area();
            var perimeterBias = Math.abs(predictedMask.perimeter() - truthMask.perimeter())
                    / (double) Math.max(1, truthMask.perimeter());
            return (areaBias + perimeterBias) / 2;
        }).sorted().boxed().toList();
        return new SampleMetrics(organ, pq, dice,
                Math.abs(predicted.size() - truth.size()) / (double) Math.max(1, truth.size()),
                biases.isEmpty() ? 1.0 : median(biases));
    }

    private static List<Mask> predicted(List<OpticalDensityWatershed.Instance> instances, int width, int height) {
        var result = new ArrayList<Mask>();
        for (var instance : instances) {
            var pixelCount = 0;
            for (var index = 0; index < instance.rle().size(); index += 2) {
                var start = instance.rle().get(index);
                var length = instance.rle().get(index + 1);
                require(start >= 0 && length > 0 && start + length <= width * height,
                        "Predicted instance RLE is invalid");
                pixelCount += length;
            }
            var pixels = new int[pixelCount];
            var cursor = 0;
            for (var index = 0; index < instance.rle().size(); index += 2) {
                var start = instance.rle().get(index);
                var length = instance.rle().get(index + 1);
                for (var offset = 0; offset < length; offset++) pixels[cursor++] = start + offset;
            }
            result.add(mask(pixels, (int) Math.round(instance.perimeterPx()), width));
        }
        return result;
    }

    private static List<Mask> groundTruth(Path annotationPath, int width, int height) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(annotationPath.toFile());
            var regions = document.getElementsByTagName("Region");
            var result = new ArrayList<Mask>();
            for (var regionIndex = 0; regionIndex < regions.getLength(); regionIndex++) {
                var region = (org.w3c.dom.Element) regions.item(regionIndex);
                var vertices = region.getElementsByTagName("Vertex");
                if (vertices.getLength() < 3) continue;
                var x = new int[vertices.getLength()];
                var y = new int[vertices.getLength()];
                for (var index = 0; index < vertices.getLength(); index++) {
                    var vertex = (org.w3c.dom.Element) vertices.item(index);
                    x[index] = (int) Math.round(Double.parseDouble(vertex.getAttribute("X")));
                    y[index] = (int) Math.round(Double.parseDouble(vertex.getAttribute("Y")));
                }
                var polygon = new Polygon(x, y, vertices.getLength());
                var bounds = polygon.getBounds().intersection(new java.awt.Rectangle(0, 0, width, height));
                var pixels = new ArrayList<Integer>();
                for (var py = bounds.y; py < bounds.y + bounds.height; py++) {
                    for (var px = bounds.x; px < bounds.x + bounds.width; px++) {
                        if (polygon.contains(px + 0.5, py + 0.5)) pixels.add(py * width + px);
                    }
                }
                if (!pixels.isEmpty()) {
                    var raw = pixels.stream().mapToInt(Integer::intValue).toArray();
                    result.add(mask(raw, perimeter(raw, width, height), width));
                }
            }
            return List.copyOf(result);
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException
                | NumberFormatException invalidXml) {
            throw new IllegalArgumentException("Cell qualification annotation is invalid", invalidXml);
        }
    }

    private static int perimeter(int[] pixels, int width, int height) {
        var included = new java.util.HashSet<Integer>(Math.max(16, pixels.length * 2));
        for (var pixel : pixels) included.add(pixel);
        var perimeter = 0;
        for (var pixel : pixels) {
            var x = pixel % width;
            var y = pixel / width;
            if (x == 0 || !included.contains(pixel - 1)) perimeter++;
            if (x + 1 == width || !included.contains(pixel + 1)) perimeter++;
            if (y == 0 || !included.contains(pixel - width)) perimeter++;
            if (y + 1 == height || !included.contains(pixel + width)) perimeter++;
        }
        return perimeter;
    }

    private static int intersection(Mask left, Mask right) {
        if (left.maxX() < right.minX() || right.maxX() < left.minX()
                || left.maxY() < right.minY() || right.maxY() < left.minY()) return 0;
        var leftPixels = left.pixels();
        var rightPixels = right.pixels();
        var leftIndex = 0;
        var rightIndex = 0;
        var count = 0;
        while (leftIndex < leftPixels.length && rightIndex < rightPixels.length) {
            if (leftPixels[leftIndex] == rightPixels[rightIndex]) {
                count++;
                leftIndex++;
                rightIndex++;
            } else if (leftPixels[leftIndex] < rightPixels[rightIndex]) leftIndex++;
            else rightIndex++;
        }
        return count;
    }

    private static Mask mask(int[] pixels, int perimeter, int width) {
        java.util.Arrays.sort(pixels);
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = -1;
        var maxY = -1;
        for (var pixel : pixels) {
            minX = Math.min(minX, pixel % width);
            maxX = Math.max(maxX, pixel % width);
            minY = Math.min(minY, pixel / width);
            maxY = Math.max(maxY, pixel / width);
        }
        return new Mask(pixels, pixels.length, perimeter, minX, minY, maxX, maxY);
    }

    private static Path relativePath(Path root, String relative) {
        require(!Path.of(relative).isAbsolute(), "Cell qualification sample path must be relative");
        var path = root.resolve(relative).normalize();
        require(path.startsWith(root) && Files.isRegularFile(path), "Cell qualification sample path is invalid");
        return path;
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node.path(field);
        require(value.isTextual() && !value.textValue().isBlank(), "Cell qualification field is invalid: " + field);
        return value.textValue();
    }

    private static void copyGate(JsonNode source, ObjectNode target, String field) {
        require(source.path(field).isNumber(), "Cell qualification gate is missing: " + field);
        target.put(field, source.path(field).doubleValue());
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        var middle = values.size() / 2;
        return values.size() % 2 == 1 ? values.get(middle) : (values.get(middle - 1) + values.get(middle)) / 2;
    }

    private static long usedHeapBytes() {
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private record Mask(int[] pixels, int area, int perimeter, int minX, int minY, int maxX, int maxY) { }
    private record Pair(int truthIndex, int predictedIndex, int intersection, double iou) { }
    private record SampleMetrics(String organ, double pq, double dice, double countError,
            double morphometryBias) { }
}
