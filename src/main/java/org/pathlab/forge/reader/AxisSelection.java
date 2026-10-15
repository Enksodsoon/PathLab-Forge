package org.pathlab.forge.reader;

import java.util.Objects;

public record AxisSelection(AxisMode mode, int start, int end) {
    public AxisSelection {
        mode = Objects.requireNonNull(mode, "mode");
        if (start < 0 || end < start || (mode == AxisMode.SLICE && start != end)) {
            throw new IllegalArgumentException("Axis selection is invalid");
        }
    }

    public static AxisSelection slice(int index) {
        return new AxisSelection(AxisMode.SLICE, index, index);
    }

    public boolean projected() {
        return mode != AxisMode.SLICE;
    }
}
