package org.pathlab.forge.reader;

public final class ImportProbeException extends Exception {
    private static final long serialVersionUID = 1L;
    private final ImportDiagnostic diagnostic;

    public ImportProbeException(ImportDiagnostic diagnostic) {
        super(diagnostic.detail());
        this.diagnostic = diagnostic;
    }

    public ImportDiagnostic diagnostic() {
        return diagnostic;
    }
}
