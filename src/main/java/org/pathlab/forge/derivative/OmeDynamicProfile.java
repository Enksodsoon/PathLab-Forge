package org.pathlab.forge.derivative;

import java.util.Objects;

public record OmeDynamicProfile(
        String id,
        int tileSize,
        int pyramidFactor,
        String codec,
        String colorSpace,
        int bitsPerSample,
        boolean stripSensitiveMetadata,
        int defaultJpegQuality) {
    public static final OmeDynamicProfile V1 = new OmeDynamicProfile(
            "ome-dynamic-v1",
            512,
            4,
            "jpeg",
            "sRGB",
            8,
            true,
            75);

    public OmeDynamicProfile {
        id = requireText(id, "id");
        codec = requireText(codec, "codec");
        colorSpace = requireText(colorSpace, "colorSpace");
        if (tileSize <= 0
                || pyramidFactor <= 1
                || bitsPerSample != 8
                || defaultJpegQuality < 1
                || defaultJpegQuality > 100) {
            throw new IllegalArgumentException("OME dynamic profile is invalid");
        }
    }

    private static String requireText(String value, String label) {
        var normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
