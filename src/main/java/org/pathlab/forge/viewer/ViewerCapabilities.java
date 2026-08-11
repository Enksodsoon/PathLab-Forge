package org.pathlab.forge.viewer;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public record ViewerCapabilities(
        Set<String> ingestModes,
        Set<ViewerOmeProfile> omeProfiles,
        long maxChunkBytes,
        long recommendedChunkBytes,
        long maxUploadBytes) {
    private static final long LEGACY_CHUNK_BYTES = 16L * 1024 * 1024;
    private static final long MAX_CHUNK_BYTES = 64L * 1024 * 1024;

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
        var modes = strings(json, "ingestModes");
        var profiles = new HashSet<ViewerOmeProfile>();
        if (!Pattern.compile("\\\"omeProfiles\\\"\\s*:").matcher(json).find()) {
            throw new IOException("Viewer response omitted omeProfiles");
        }
        var objectMatch = Pattern.compile("\\{(.*?)\\}", Pattern.DOTALL)
                .matcher(json);
        while (objectMatch.find()) {
            var node = objectMatch.group();
            if (!Pattern.compile("\\\"id\\\"\\s*:").matcher(node).find()) {
                continue;
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
                    strings(node, "tiffKinds"),
                    requiredBoolean(node, "nativeJpegTiles"),
                    requiredBoolean(node, "persistedSha256")));
        }
        return new ViewerCapabilities(
                modes,
                profiles,
                requiredPositive(json, "maxChunkBytes"),
                requiredPositive(json, "recommendedChunkBytes"),
                requiredPositive(json, "maxUploadBytes"));
    }

    private static String requiredText(String json, String field) throws IOException {
        var match = Pattern.compile(
                        "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(json);
        if (!match.find()) {
            throw new IOException("Viewer response omitted " + field);
        }
        return match.group(1);
    }

    private static long requiredPositive(String json, String field) throws IOException {
        var match = Pattern.compile(
                        "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(\\d+)")
                .matcher(json);
        if (!match.find()) {
            throw new IOException("Viewer response omitted " + field);
        }
        var value = Long.parseLong(match.group(1));
        if (value < 1) {
            throw new IOException("Viewer response contained invalid " + field);
        }
        return value;
    }

    private static boolean requiredBoolean(String json, String field) throws IOException {
        var match = Pattern.compile(
                        "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(true|false)")
                .matcher(json);
        if (!match.find()) {
            throw new IOException("Viewer response omitted " + field);
        }
        return Boolean.parseBoolean(match.group(1));
    }

    private static Set<String> strings(String json, String field) throws IOException {
        var match = Pattern.compile(
                        "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\[([^]]*)]")
                .matcher(json);
        if (!match.find()) {
            throw new IOException("Viewer response omitted " + field);
        }
        var values = new HashSet<String>();
        var item = Pattern.compile("\\\"([^\\\"]+)\\\"").matcher(match.group(1));
        while (item.find()) {
            values.add(item.group(1));
        }
        return values;
    }

    public int uploadChunkBytes() {
        return Math.toIntExact(Math.min(
                MAX_CHUNK_BYTES, Math.min(maxChunkBytes, recommendedChunkBytes)));
    }
}
