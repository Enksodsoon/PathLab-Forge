package org.pathlab.forge.library;

public enum DatasetFormat {
    OME_TIFF,
    VSI,
    SVS;

    public boolean isSingleFileTiff() {
        return this == OME_TIFF || this == SVS;
    }
}
