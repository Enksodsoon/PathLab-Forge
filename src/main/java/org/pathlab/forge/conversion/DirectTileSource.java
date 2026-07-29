package org.pathlab.forge.conversion;

public record DirectTileSource(int width, int height, int tileSize) {
    public DirectTileSource {
        if (width <= 0 || height <= 0 || tileSize <= 0) {
            throw new IllegalArgumentException("Direct tile geometry is invalid");
        }
    }

    public int maximumLevel() {
        var dimension = Math.max(width, height);
        return 32 - Integer.numberOfLeadingZeros(dimension - 1);
    }
}
