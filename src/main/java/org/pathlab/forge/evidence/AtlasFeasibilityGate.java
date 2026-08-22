package org.pathlab.forge.evidence;

/** Deterministic decision gate for the bounded local Atlas-H&E feasibility probe. */
public final class AtlasFeasibilityGate {
    private static final double MAX_LOCAL_HOURS = 7 * 24;
    private AtlasFeasibilityGate() {}

    public static Decision decide(Probe probe) {
        if (probe.elapsedHours < 4 || probe.completedSteps <= probe.warmupSteps
                || probe.totalSteps <= probe.completedSteps) return new Decision("PROBE_INCOMPLETE", null);
        if (probe.peakVramMiB > 4608 || probe.peakRamMiB > 16_384 || probe.oomCount > 0
                || !probe.validationImproved) return new Decision("LOCAL_INSUFFICIENT", null);
        var effectiveSteps = probe.completedSteps - probe.warmupSteps;
        var effectiveHours = probe.elapsedHours * effectiveSteps / probe.completedSteps;
        var stepsPerHour = effectiveSteps / effectiveHours;
        var projected = (probe.totalSteps - probe.completedSteps) / stepsPerHour;
        return new Decision(projected <= MAX_LOCAL_HOURS ? "CONTINUE_LOCAL" : "LOCAL_INSUFFICIENT",
                projected);
    }

    public record Probe(double elapsedHours, long completedSteps, long warmupSteps, long totalSteps,
            int peakVramMiB, int peakRamMiB, int oomCount, boolean validationImproved) {}
    public record Decision(String status, Double projectedRemainingHours) {}
}
