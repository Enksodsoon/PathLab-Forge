package org.pathlab.forge.reader;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ImportDiagnostic(Code code, String detail, List<Path> paths) {
    public ImportDiagnostic {
        code = Objects.requireNonNull(code, "code");
        detail = Objects.requireNonNull(detail, "detail").trim();
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        if (detail.isEmpty()) throw new IllegalArgumentException("Diagnostic detail is required");
    }

    public enum Code {
        UNSUPPORTED(false),
        CORRUPT(false),
        ENCRYPTED(false),
        MISSING_COMPANION(true),
        CODEC_UNAVAILABLE(true),
        PROBE_TIMEOUT(false),
        RESOURCE_LIMIT(true);

        private final boolean repairable;

        Code(boolean repairable) {
            this.repairable = repairable;
        }

        public boolean repairable() {
            return repairable;
        }
    }
}
