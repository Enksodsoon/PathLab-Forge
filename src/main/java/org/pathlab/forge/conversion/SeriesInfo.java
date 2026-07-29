package org.pathlab.forge.conversion;

import java.util.Objects;

public record SeriesInfo(
        int index,
        String name,
        int width,
        int height,
        int channels,
        int sizeZ,
        int sizeT,
        String pixelType,
        double physicalSizeX,
        double physicalSizeY,
        String physicalUnit,
        int resolutionCount) {
    public SeriesInfo {
        if (index < 0 || width <= 0 || height <= 0 || channels <= 0 || sizeZ <= 0 || sizeT <= 0) {
            throw new IllegalArgumentException("Series dimensions and index are invalid");
        }
        name = Objects.requireNonNullElse(name, "").trim();
        pixelType = Objects.requireNonNullElse(pixelType, "").trim();
        physicalUnit = Objects.requireNonNullElse(physicalUnit, "").trim();
        if (resolutionCount <= 0) {
            throw new IllegalArgumentException("Resolution count must be positive");
        }
    }

    public SeriesInfo(
            int index,
            String name,
            int width,
            int height,
            int channels,
            int sizeZ,
            int sizeT,
            String pixelType,
            double physicalSizeX,
            double physicalSizeY,
            String physicalUnit) {
        this(
                index,
                name,
                width,
                height,
                channels,
                sizeZ,
                sizeT,
                pixelType,
                physicalSizeX,
                physicalSizeY,
                physicalUnit,
                1);
    }

    public SeriesInfo withResolutionCount(int count) {
        return new SeriesInfo(
                index,
                name,
                width,
                height,
                channels,
                sizeZ,
                sizeT,
                pixelType,
                physicalSizeX,
                physicalSizeY,
                physicalUnit,
                count);
    }

    public boolean isRgbPlane() {
        return channels == 3 && sizeZ == 1 && sizeT == 1;
    }
}
