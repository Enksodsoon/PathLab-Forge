package org.pathlab.forge.viewer;

import java.util.Set;

public record ViewerOmeProfile(
        String id,
        String pixelType,
        int channels,
        String colorSpace,
        int tileWidth,
        int tileHeight,
        int pyramidFactor,
        String compression,
        Set<String> tiffKinds,
        boolean nativeJpegTiles,
        boolean persistedSha256) {
    public ViewerOmeProfile {
        tiffKinds = Set.copyOf(tiffKinds);
    }

    public boolean isExactDynamicV1() {
        return "ome-dynamic-v1".equals(id)
                && "uint8".equals(pixelType)
                && channels == 3
                && "sRGB".equals(colorSpace)
                && tileWidth == 512
                && tileHeight == 512
                && pyramidFactor == 2
                && "jpeg".equals(compression)
                && tiffKinds.containsAll(Set.of("classic", "bigtiff"))
                && nativeJpegTiles
                && persistedSha256;
    }
}
