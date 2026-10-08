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
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public Result build(
            Path output,
            String artifactRevisionId,
            String slideSha256,
            List<AnnotationRecord> annotations) throws IOException {
        return buildContents(output, artifactRevisionId, slideSha256, annotations, List.of(), null, List.of(), 0, 0, 1, 1, 1);
    }

    /** Current view identity is supplied by the root service after its source/view freshness checks. */
    public record AcceptedAnalysis(org.pathlab.forge.analysis.AnalysisRun run,
            org.pathlab.forge.analysis.AnalysisReview review, String currentViewRevision) {}

    public Result build(Path output, org.pathlab.forge.conversion.ArtifactRevision revision,
            List<AnnotationRecord> annotations, List<AcceptedAnalysis> accepted,
            int cropX, int cropY, int cropWidth, int cropHeight, double downsample) throws IOException {
        var transformed = annotations.stream().sorted(java.util.Comparator.comparing(AnnotationRecord::id))
                .map(annotation -> org.pathlab.forge.annotation.AnnotationTransformer.transform(annotation.geometry(),
                        cropX, cropY, cropWidth, cropHeight, downsample)
                    .map(geometry -> new AnnotationRecord(annotation.id(), annotation.type(), geometry,
                        annotation.label(), annotation.color(), annotation.createdAt(), annotation.parentId(),
                        annotation.classification(), annotation.updatedAt(), annotation.revision())))
                .flatMap(java.util.Optional::stream).toList();
        return buildContents(output, revision.id(), revision.omeSha256(), transformed, accepted, revision,
                annotations, cropX, cropY, cropWidth, cropHeight, downsample);
    }

    private Result buildContents(Path output, String artifactRevisionId, String slideSha256,
            List<AnnotationRecord> annotations, List<AcceptedAnalysis> accepted,
            org.pathlab.forge.conversion.ArtifactRevision revision, List<AnnotationRecord> originalAnnotations,
            int cropX, int cropY, int cropWidth, int cropHeight, double downsample) throws IOException {
        var runs = new StringBuilder(JSON.writeValueAsString(Map.of(
                "id", "forge-manual-annotations",
                "status", "complete",
                "stale", false,
                "provenance", Map.of("source", "PathLab Forge", "kind", "manual")))).append("\n");
        var objects = new StringBuilder();
        var measurements = new StringBuilder();
        for (var annotation : annotations.stream().sorted(java.util.Comparator.comparing(AnnotationRecord::id)).toList()) {
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
        var reviews = new StringBuilder();
        for (var item : accepted.stream().sorted(java.util.Comparator.comparing(entry -> entry.run().id())).toList()) {
            var run = item.run(); var review = item.review(); var provenance = run.provenance();
            if (provenance == null || revision == null || !"SUCCEEDED".equals(run.status()) || run.stale()
                    || !org.pathlab.forge.analysis.DeterministicAnalysisService.TOOLS.contains(run.tool())
                    || !run.datasetId().equals(revision.datasetId()) || !provenance.sourceFingerprint().equals(revision.sourceFingerprint()))
                throw new IOException("Only current deterministic analyses of this artifact source can be delivered");
            if (review.revision() < 1 || !review.runId().equals(run.id()) || provenance == null
                    || item.currentViewRevision() == null || item.currentViewRevision().isBlank()
                    || !item.currentViewRevision().equals(provenance.viewRevision()))
                throw new IOException("Analysis review or view scope is not current");
            var roi = originalAnnotations.stream().filter(annotation -> annotation.id().equals(run.annotationId())).findFirst()
                    .orElseThrow(() -> new IOException("Analysis ROI is not present in this delivery"));
            if (roi.revision() != provenance.annotationRevision() || !roi.type().equals(provenance.annotationType())
                    || !roi.geometry().equals(provenance.annotationGeometry()))
                throw new IOException("Analysis ROI scope changed after the run");
            var exportProvenance = new LinkedHashMap<String,Object>();
            exportProvenance.put("source", "PathLab Forge"); exportProvenance.put("kind", "deterministic");
            exportProvenance.put("originalRunId", run.id()); exportProvenance.put("originalScope", provenance);
            exportProvenance.put("configuration", run.configuration()); exportProvenance.put("reviewRevision", review.revision());
            exportProvenance.put("exportTransform", Map.of("cropX",cropX,"cropY",cropY,"cropWidth",cropWidth,"cropHeight",cropHeight,"downsample",downsample));
            runs.append(JSON.writeValueAsString(Map.of("id",run.id(),"status","complete","stale",false,"provenance",exportProvenance))).append('\n');
            reviews.append(JSON.writeValueAsString(Map.of("runId",run.id(),"revision",review.revision(),"originalScope",provenance,
                    "objects",review.objects().stream().sorted(java.util.Comparator.comparing(org.pathlab.forge.analysis.PathObject::id)).toList(),"stainVector",review.stainVector()))).append('\n');
            if (annotations.stream().anyMatch(annotation -> annotation.id().equals(roi.id()))) {
                appendNumericMeasurements(measurements, roi.id(), "run:" + run.id(), JSON.valueToTree(run.outputs()), provenance.units());
                if (!review.stainVector().isEmpty()) appendNumericMeasurements(measurements, roi.id(), "run:" + run.id() + ":reviewedStainVector", JSON.valueToTree(review.stainVector()), "");
            }
            for (var original : review.objects().stream().sorted(java.util.Comparator.comparing(org.pathlab.forge.analysis.PathObject::id)).toList()) {
                if (!acceptedObject(original)) continue;
                if (!original.sourceRunId().equals(run.id()) || !original.datasetId().equals(run.datasetId())
                        || !original.parentId().equals(roi.id())) throw new IOException("Accepted object is outside its immutable run scope");
                var transformed = org.pathlab.forge.annotation.AnnotationTransformer.transform(original.geometry(),cropX,cropY,cropWidth,cropHeight,downsample);
                if (transformed.isEmpty()) continue;
                var id = run.id() + ":" + original.id();
                var type = original.properties().getOrDefault("geometryType",original.kind() == org.pathlab.forge.analysis.PathObject.Kind.TMA_CORE ? "rectangle" : "point");
                objects.append(JSON.writeValueAsString(Map.of("id",id,"runId",run.id(),"type",original.kind().name().toLowerCase(java.util.Locale.ROOT),
                        "parentId",roi.id(),"classification",original.classification(),"geometry",geometry(type,transformed.get()),
                        "revision",original.revision(),"originalObjectId",original.id()))).append('\n');
                for (var measure : GeometryMeasurements.measure(type,transformed.get()).entrySet())
                    measurements.append(JSON.writeValueAsString(Map.of("objectId",id,"name",measure.getKey(),"value",measure.getValue(),"unit",unit(measure.getKey())))).append('\n');
            }
        }
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("manifest.json", JSON.writeValueAsBytes(Map.of("schema","pathlab-private-results/v1",
                "artifactRevisionId",artifactRevisionId,"slideSha256",slideSha256,"objectCount",objects.toString().lines().count())));
        entries.put("objects.ndjson", objects.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("measurements.ndjson", measurements.toString().getBytes(StandardCharsets.UTF_8));
        entries.put("runs.ndjson", runs.toString().getBytes(StandardCharsets.UTF_8));
        if (!accepted.isEmpty()) entries.put("reviews.ndjson", reviews.toString().getBytes(StandardCharsets.UTF_8));
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

    private static void appendNumericMeasurements(StringBuilder output, String objectId, String name,
            com.fasterxml.jackson.databind.JsonNode value, String unit) throws IOException {
        if (value.isNumber()) {
            double number = value.doubleValue();
            if (!Double.isFinite(number)) throw new IOException("Nonfinite analysis measurement");
            output.append(JSON.writeValueAsString(Map.of("objectId",objectId,"name",name,"value",number,"unit",unit))).append('\n');
        } else if (value.isObject()) {
            var names = new java.util.TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            for (var child : names) appendNumericMeasurements(output,objectId,name + ":" + child,value.get(child),unit);
        } else if (value.isArray()) {
            for (int index=0;index<value.size();index++) appendNumericMeasurements(output,objectId,name + ":" + index,value.get(index),unit);
        }
    }

    private static boolean acceptedObject(org.pathlab.forge.analysis.PathObject object) {
        return object.kind() != org.pathlab.forge.analysis.PathObject.Kind.AI_SUGGESTION
                && "true".equals(object.properties().get("accepted")) && !"true".equals(object.properties().get("missing"));
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

    static String sha256(Path path) throws IOException {
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
