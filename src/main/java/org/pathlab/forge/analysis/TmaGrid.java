package org.pathlab.forge.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TmaGrid {
    private TmaGrid() {}

    public static List<PathObject> create(
            String datasetId, String parentId, int rows, int columns,
            double x, double y, double width, double height) {
        if (rows < 1 || rows > 100 || columns < 1 || columns > 100
                || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("TMA grid dimensions are invalid");
        }
        var cellWidth = width / columns;
        var cellHeight = height / rows;
        var cores = new ArrayList<PathObject>(Math.multiplyExact(rows, columns));
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var left = x + column * cellWidth;
                var top = y + row * cellHeight;
                var geometry = left + "," + top + ";" + (left + cellWidth) + "," + (top + cellHeight);
                cores.add(new PathObject(
                        UUID.randomUUID().toString(), datasetId, parentId,
                        PathObject.Kind.TMA_CORE, geometry,
                        "TMA " + (row + 1) + "-" + (column + 1), "",
                        Map.of("row", Integer.toString(row), "column", Integer.toString(column),
                                "missing", "false"), 1));
            }
        }
        return List.copyOf(cores);
    }
}
