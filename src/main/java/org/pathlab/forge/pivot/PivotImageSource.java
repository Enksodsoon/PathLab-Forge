package org.pathlab.forge.pivot;

import java.io.IOException;

/** A bounded byte source for one DZI and its JPEG tiles. */
public interface PivotImageSource {
    String revision();

    int imageWidth();

    int imageHeight();

    double sourceOriginX();

    double sourceOriginY();

    double sourceScaleX();

    double sourceScaleY();

    byte[] descriptor() throws IOException;

    byte[] tile(int level, int x, int y, String format) throws IOException;
}
