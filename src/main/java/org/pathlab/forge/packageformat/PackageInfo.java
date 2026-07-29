package org.pathlab.forge.packageformat;

import java.nio.file.Path;

public record PackageInfo(
        Path path,
        long bytes,
        String sha256,
        long derivativeBytes,
        int derivativeFileCount) {}
