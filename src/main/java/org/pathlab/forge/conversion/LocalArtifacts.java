package org.pathlab.forge.conversion;

import java.nio.file.Path;

public record LocalArtifacts(
        Path omeTiff, Path derivativeRoot, Path dzi, Path thumbnail, Path packagePath) {}
