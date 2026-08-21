package org.pathlab.forge.evidence;

public enum EvidenceJobState {
    QUEUED(false),
    VALIDATING(false),
    RUNNING(false),
    REFINING(false),
    PACKAGING(false),
    COMPLETED(true),
    ABSTAINED(true),
    UNSUPPORTED(true),
    FAILED(true),
    CANCELLED(true);

    private final boolean terminal;
    EvidenceJobState(boolean terminal) { this.terminal = terminal; }
    public boolean terminal() { return terminal; }
}
