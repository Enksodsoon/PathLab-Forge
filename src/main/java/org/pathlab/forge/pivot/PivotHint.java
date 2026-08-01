package org.pathlab.forge.pivot;

import java.util.Objects;

public record PivotHint(String text, PivotSession session) {
    public PivotHint {
        text = Objects.requireNonNull(text, "text");
        session = Objects.requireNonNull(session, "session");
    }
}
