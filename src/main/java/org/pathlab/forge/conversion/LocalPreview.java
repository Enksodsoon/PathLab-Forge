package org.pathlab.forge.conversion;

import java.nio.file.Path;

public record LocalPreview(Path root, int width, int height, int sourceWidth, int sourceHeight) {}
