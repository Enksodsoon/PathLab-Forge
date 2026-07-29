package org.pathlab.forge.derivative;

import java.io.IOException;
import java.nio.file.Path;

public interface DerivativeEngine {
    boolean available();

    String description();

    void optimizeOme(Path renderedOme, Path pyramidalOme) throws IOException;

    DerivativeInfo generateDzi(Path omeTiff, Path outputRoot, int width, int height)
            throws IOException;
}
