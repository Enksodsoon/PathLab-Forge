package org.pathlab.forge.conversion;

public record RgbRegion(int x, int y, int width, int height, byte[] interleavedRgb) {
    public RgbRegion {
        if (width < 1 || height < 1
                || interleavedRgb == null
                || interleavedRgb.length != Math.multiplyExact(Math.multiplyExact(width, height), 3)) {
            throw new IllegalArgumentException("RGB region dimensions do not match its pixels");
        }
        interleavedRgb = interleavedRgb.clone();
    }

    @Override
    public byte[] interleavedRgb() {
        return interleavedRgb.clone();
    }
}
