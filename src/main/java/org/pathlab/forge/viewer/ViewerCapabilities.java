package org.pathlab.forge.viewer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public record ViewerCapabilities(
        Set<String> ingestModes,
        Set<ViewerOmeProfile> omeProfiles,
        long maxChunkBytes,
        long recommendedChunkBytes,
        long maxUploadBytes) {
    private static final long LEGACY_CHUNK_BYTES = 16L * 1024 * 1024;
    private static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    public ViewerCapabilities {
        ingestModes = Set.copyOf(ingestModes);
        omeProfiles = Set.copyOf(omeProfiles);
        if (maxChunkBytes < 1 || recommendedChunkBytes < 1 || maxUploadBytes < 1) {
            throw new IllegalArgumentException("Viewer chunk sizes must be positive");
        }
    }

    public static ViewerCapabilities legacy() {
        return new ViewerCapabilities(
                Set.of("prepared-v2"), Set.of(), LEGACY_CHUNK_BYTES, LEGACY_CHUNK_BYTES, Long.MAX_VALUE);
    }

    public boolean supportsDynamicOme() {
        return ingestModes.contains("ome-dynamic-v1")
                && omeProfiles.stream().anyMatch(ViewerOmeProfile::isExactDynamicV1);
    }

    public boolean accepts(long artifactBytes) {
        return artifactBytes > 0 && artifactBytes <= maxUploadBytes;
    }

    public static ViewerCapabilities parse(String json) throws IOException {
        try {
            var root = JSON.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IOException("Viewer response must be a JSON object");
            }
            var profiles = new HashSet<ViewerOmeProfile>();
            var profileNodes = requiredArray(root, "omeProfiles");
            for (var node : profileNodes) {
                if (!node.isObject()) {
                    throw new IOException("Viewer response contained invalid omeProfiles");
                }
                profiles.add(new ViewerOmeProfile(
                        requiredText(node, "id"),
                        requiredText(node, "pixelType"),
                        Math.toIntExact(requiredPositive(node, "channels")),
                        requiredText(node, "colorSpace"),
                        Math.toIntExact(requiredPositive(node, "tileWidth")),
                        Math.toIntExact(requiredPositive(node, "tileHeight")),
                        Math.toIntExact(requiredPositive(node, "pyramidFactor")),
                        requiredText(node, "compression"),
                        Math.toIntExact(requiredPositive(node, "jpegQuality")),
                        strings(node, "tiffKinds"),
                        requiredBoolean(node, "nativeJpegTiles"),
                        requiredBoolean(node, "persistedSha256")));
            }
            return new ViewerCapabilities(
                    strings(root, "ingestModes"),
                    profiles,
                    requiredPositive(root, "maxChunkBytes"),
                    requiredPositive(root, "recommendedChunkBytes"),
                    requiredPositive(root, "maxUploadBytes"));
        } catch (JsonProcessingException | ArithmeticException | IllegalArgumentException error) {
            throw new IOException("Viewer capabilities are malformed", error);
        }
    }

    private static JsonNode requiredArray(JsonNode parent, String field) throws IOException {
        var value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw new IOException("Viewer response omitted " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) throws IOException {
        var value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("Viewer response omitted " + field);
        }
        return value.textValue();
    }

    private static long requiredPositive(JsonNode parent, String field) throws IOException {
        var value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1) {
            throw new IOException("Viewer response contained invalid " + field);
        }
        return value.longValue();
    }

    private static boolean requiredBoolean(JsonNode parent, String field) throws IOException {
        var value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IOException("Viewer response omitted " + field);
        }
        return value.booleanValue();
    }

    private static Set<String> strings(JsonNode parent, String field) throws IOException {
        var values = new HashSet<String>();
        for (var item : requiredArray(parent, field)) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IOException("Viewer response contained invalid " + field);
            }
            values.add(item.textValue());
        }
        return values;
    }

    public int uploadChunkBytes() {
        return Math.toIntExact(Math.min(
                MAX_CHUNK_BYTES, Math.min(maxChunkBytes, recommendedChunkBytes)));
    }
}
