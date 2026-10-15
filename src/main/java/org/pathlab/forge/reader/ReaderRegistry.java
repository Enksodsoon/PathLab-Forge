package org.pathlab.forge.reader;

import java.util.Comparator;
import java.util.List;

public final class ReaderRegistry {
    private ReaderRegistry() {}

    public static ReaderCandidate select(List<ReaderCandidate> candidates)
            throws ReaderSelectionException {
        return candidates.stream()
                .max(Comparator.comparingInt(ReaderRegistry::correctnessScore)
                        .thenComparingLong(candidate -> -candidate.startupMillis())
                        .thenComparing(candidate -> candidate.descriptor().readerId(),
                                Comparator.reverseOrder()))
                .orElseThrow(() -> new ReaderSelectionException(
                        "No installed reader could open this dataset"));
    }

    private static int correctnessScore(ReaderCandidate candidate) {
        var descriptor = candidate.descriptor();
        var score = 0;
        if (descriptor.multidimensional()) score += 16;
        if (descriptor.groupedFiles()) score += 8;
        if (descriptor.nativePyramid()) score += 4;
        if (descriptor.randomRegions()) score += 2;
        return score;
    }
}
