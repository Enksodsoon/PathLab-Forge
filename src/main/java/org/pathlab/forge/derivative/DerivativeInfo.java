package org.pathlab.forge.derivative;

import java.nio.file.Path;

public record DerivativeInfo(
        Path root, long bytes, int fileCount, int tileCount, String sha256) {}
