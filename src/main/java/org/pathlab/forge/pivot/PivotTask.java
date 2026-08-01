package org.pathlab.forge.pivot;

import java.util.Objects;

public record PivotTask(
        String id,
        String queryFile,
        double targetX,
        double targetY,
        double targetWidth,
        double targetHeight,
        int previewLevel,
        int tileX,
        int tileY,
        int scaleGap,
        double tissueFraction,
        double ambiguity,
        double difficulty) {
    public PivotTask {
        id = requireText(id, "id");
        queryFile = requireText(queryFile, "queryFile");
        if (!queryFile.matches("[A-Za-z0-9._-]+\\.jpg")) {
            throw new IllegalArgumentException("Query file name is invalid");
        }
        if (targetX < 0 || targetY < 0 || targetWidth <= 0 || targetHeight <= 0
                || previewLevel < 0 || tileX < 0 || tileY < 0
                || scaleGap < 1 || !unitInterval(tissueFraction)
                || !unitInterval(ambiguity) || !unitInterval(difficulty)) {
            throw new IllegalArgumentException("PIVOT task metadata is invalid");
        }
    }

    public double centerX() {
        return targetX + targetWidth / 2;
    }

    public double centerY() {
        return targetY + targetHeight / 2;
    }

    private static boolean unitInterval(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
