package org.pathlab.forge.library;

import java.util.Locale;
import java.util.Objects;

public record DatasetFormat(String name) {
    public static final DatasetFormat OME_TIFF = new DatasetFormat("OME_TIFF");
    public static final DatasetFormat VSI = new DatasetFormat("VSI");
    public static final DatasetFormat SVS = new DatasetFormat("SVS");

    public DatasetFormat {
        name = Objects.requireNonNull(name, "name").trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty() || !name.matches("[A-Z0-9_]{1,96}")) {
            throw new IllegalArgumentException("Dataset format name is invalid");
        }
    }

    public static DatasetFormat valueOf(String name) {
        var value = new DatasetFormat(name);
        if (value.equals(OME_TIFF)) return OME_TIFF;
        if (value.equals(VSI)) return VSI;
        if (value.equals(SVS)) return SVS;
        return value;
    }

    public boolean isSingleFileTiff() {
        return equals(OME_TIFF) || equals(SVS);
    }
}
