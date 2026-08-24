package org.pathlab.forge.evidence;

import java.time.Duration;
import java.util.List;

/** Frozen four-hour gate for resumable 15-20 minute Atlas probe segments. */
public final class AtlasFeasibilityGate {
    public static final Duration REQUIRED_PROBE = Duration.ofHours(4);
    public static final Duration MIN_SEGMENT = Duration.ofMinutes(15);
    public static final Duration MAX_SEGMENT = Duration.ofMinutes(20);
    public static final Duration MAX_PROJECTED_COMPLETION = Duration.ofDays(7);
    public static final long MAX_RAM_BYTES = 16L * 1024 * 1024 * 1024;
    public static final long MAX_VRAM_BYTES = 4608L * 1024 * 1024;

    private AtlasFeasibilityGate() { }

    public static Decision evaluate(List<Segment> segments, Duration projectedCompletion) {
        if (segments == null || segments.isEmpty() || projectedCompletion == null) {
            return new Decision("not_evaluable", "ATLAS_PROBE_MISSING", false);
        }
        if (segments.stream().anyMatch(segment -> segment.duration().compareTo(MIN_SEGMENT) < 0
                || segment.duration().compareTo(MAX_SEGMENT) > 0 || !segment.checkpointVerified())) {
            return new Decision("not_evaluable", "ATLAS_SEGMENT_OR_CHECKPOINT_INVALID", false);
        }
        if (segments.stream().anyMatch(segment -> segment.peakRamBytes() > MAX_RAM_BYTES
                || segment.peakVramBytes() > MAX_VRAM_BYTES)) {
            return new Decision("experimental", "LOCAL_INSUFFICIENT_RESOURCE_LIMIT", true);
        }
        var elapsed = segments.stream().map(Segment::duration).reduce(Duration.ZERO, Duration::plus);
        if (elapsed.compareTo(REQUIRED_PROBE) < 0) {
            return new Decision("not_evaluable", "ATLAS_FOUR_HOUR_PROBE_INCOMPLETE", false);
        }
        var first = segments.get(0).validationMetric();
        var last = segments.get(segments.size() - 1).validationMetric();
        if (!(Double.isFinite(first) && Double.isFinite(last) && last > first)) {
            return new Decision("experimental", "LOCAL_INSUFFICIENT_NO_VALIDATION_IMPROVEMENT", true);
        }
        if (projectedCompletion.compareTo(MAX_PROJECTED_COMPLETION) > 0) {
            return new Decision("experimental", "LOCAL_INSUFFICIENT_PROJECTED_OVER_SEVEN_DAYS", true);
        }
        return new Decision("experimental", "LOCAL_PROBE_PASSED_FULL_QUALIFICATION_PENDING", false);
    }

    public static void requireQuote(long allInUsdCents, int gpuMemoryGiB) {
        if (allInUsdCents < 1 || allInUsdCents > 10_000 || gpuMemoryGiB < 24) {
            throw new IllegalArgumentException("Atlas rental quote exceeds the frozen approval envelope");
        }
    }

    public record Segment(Duration duration, long peakRamBytes, long peakVramBytes,
            double validationMetric, boolean checkpointVerified) { }
    public record Decision(String status, String reason, boolean paidComputeQuoteAllowed) { }
}
