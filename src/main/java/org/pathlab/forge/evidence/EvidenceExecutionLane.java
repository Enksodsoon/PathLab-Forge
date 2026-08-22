package org.pathlab.forge.evidence;

/** Execution lanes are assigned from signed pack metadata, never from IPC input. */
public enum EvidenceExecutionLane {
    GPU("gpu"), CPU_IO("cpu_io");
    private final String wire;
    EvidenceExecutionLane(String wire) { this.wire = wire; }
    public String wire() { return wire; }
    public static EvidenceExecutionLane fromWire(String value) {
        for (var lane : values()) if (lane.wire.equals(value)) return lane;
        throw new IllegalArgumentException("Unknown evidence execution lane");
    }
}
