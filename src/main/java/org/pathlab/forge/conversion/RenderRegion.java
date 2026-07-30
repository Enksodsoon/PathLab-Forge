package org.pathlab.forge.conversion;

public record RenderRegion(int x, int y, int width, int height) {
    public RenderRegion {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Render region geometry is invalid");
        }
    }
}
