package org.pathlab.forge.viewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.pathlab.forge.analysis.GeometryMeasurements;
import org.pathlab.forge.annotation.AnnotationRecord;

public final class PrivateResultsBundleBuilder {
    private static final int TAR_BLOCK = 512;
    private static final ObjectMapper JSON = new ObjectMapper();

    public Result build(
            Path output,
            String artifactRevisionId,
            String slideSha256,
            List<AnnotationRecord> annotations) throws IOException {
        var manifest = JSON.writeValueAsBytes(Map.of(
                "schema", "pathlab-private-results/v1",
                "artifactRevisionId", artifactRevisionId,
                "slideSha256", slideSha256,
                "objectCount", annotations.size()));
        var runs = JSON.writeValueAsString(Map.of(
                "id", "forge-manual-annotations",
                "status", "complete",
                "stale", false,
                "provenance", Map.of("source", "PathLab Forge", "kind", "manual"))) + "\n";
        var objects = new StringBuilder();
        var measurements = new StringBuilder();
        for (var annotation : annotations) {
            var object = new LinkedHashMap<String, Object>();
            object.put("id", annotation.id());
            object.put("runId", "forge-manual-annotations");
            object.put("type", "annotation");
            object.put("annotationType", annotation.type());
            object.put("parentId", annotation.parentId());
            object.put("classification", annotation.classification());
            object.put("label", annotation.label());
            object.put("geometry", geometry(annotation.type(), annotation.geometry()));
            object.put("style", Map.of("color", annotation.color()));
            object.put("revision", annotation.revision());
            objects.append(JSON.writeValueAsString(object)).append('\n');
            for (var entry : GeometryMeasurements.measure(
                    annotation.type(), annotation.geometry()).entrySet()) {
                measurements.append(JSON.writeValueAsString(Map.of(
                        "objectId", annotation.id(),
                        "name", entry.getKey(),
                        "value", entry.getValue(),
                        "unit", unit(entry.getKey())))).append('\n');
            }
        }
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("manifest.json", manifest);
        entries.put("objects.ndjson", objects.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("measurements.ndjson", measurements.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("runs.ndjson", runs.getBytes(StandardCharsets.UTF_8));
        var normalized = output.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        var partial = normalized.resolveSibling(normalized.getFileName() + ".partial");
        try {
            try (var raw = Files.newOutputStream(partial);
                    var gzip = new GZIPOutputStream(raw, 1024 * 1024)) {
                for (var entry : entries.entrySet()) {
                    writeEntry(gzip, entry.getKey(), entry.getValue());
                }
                gzip.write(new byte[TAR_BLOCK * 2]);
            }
            try {
                Files.move(partial, normalized, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(partial, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
        return new Result(normalized, Files.size(normalized), sha256(normalized));
    }

    private static Map<String, Object> geometry(String type, String encoded) {
        var points = new ArrayList<Map<String, Double>>();
        for (var point : encoded.split(";")) {
            var parts = point.split(",", -1);
            points.add(Map.of("x", Double.parseDouble(parts[0]),
                    "y", Double.parseDouble(parts[1])));
        }
        return Map.of("type", type, "points", List.copyOf(points));
    }

    private static String unit(String measurement) {
        if (measurement.endsWith("Px2")) return "px2";
        if (measurement.endsWith("Px") || measurement.equals("x") || measurement.equals("y")) {
            return "px";
        }
        if (measurement.endsWith("Degrees")) return "degree";
        return "";
    }

    private static void writeEntry(OutputStream output, String name, byte[] payload)
            throws IOException {
        if (name.length() > 100 || name.startsWith("/") || name.contains("..")) {
            throw new IOException("Unsafe result entry name");
        }
        var header = new byte[TAR_BLOCK];
        put(header, 0, 100, name);
        put(header, 100, 8, "0000644");
        put(header, 108, 8, "0000000");
        put(header, 116, 8, "0000000");
        put(header, 124, 12, String.format("%011o", payload.length));
        put(header, 136, 12, "00000000000");
        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        put(header, 257, 6, "ustar");
        put(header, 263, 2, "00");
        var checksum = 0;
        for (var value : header) checksum += value & 0xff;
        put(header, 148, 8, String.format("%06o\0 ", checksum));
        output.write(header);
        try (InputStream input = new ByteArrayInputStream(payload)) {
            input.transferTo(output);
        }
        var padding = (TAR_BLOCK - payload.length % TAR_BLOCK) % TAR_BLOCK;
        output.write(new byte[padding]);
    }

    private static void put(byte[] target, int offset, int length, String value) {
        var bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Result(Path path, long bytes, String sha256) {}
}
