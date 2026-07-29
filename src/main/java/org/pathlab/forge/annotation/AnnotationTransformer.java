package org.pathlab.forge.annotation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AnnotationTransformer {
    private AnnotationTransformer() {}

    public static Optional<String> transform(
            String geometry,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight,
            double downsample) {
        if (cropX < 0
                || cropY < 0
                || cropWidth <= 0
                || cropHeight <= 0
                || !Double.isFinite(downsample)
                || downsample <= 0) {
            throw new IllegalArgumentException("Annotation transform is invalid");
        }
        var points = parse(geometry);
        var minimumX = points.stream().mapToDouble(Point::x).min().orElseThrow();
        var maximumX = points.stream().mapToDouble(Point::x).max().orElseThrow();
        var minimumY = points.stream().mapToDouble(Point::y).min().orElseThrow();
        var maximumY = points.stream().mapToDouble(Point::y).max().orElseThrow();
        var cropRight = (double) cropX + cropWidth;
        var cropBottom = (double) cropY + cropHeight;
        if (maximumX < cropX
                || maximumY < cropY
                || minimumX > cropRight
                || minimumY > cropBottom) {
            return Optional.empty();
        }
        return Optional.of(points.stream()
                .map(point -> decimal((point.x() - cropX) / downsample)
                        + ","
                        + decimal((point.y() - cropY) / downsample))
                .collect(java.util.stream.Collectors.joining(";")));
    }

    private static List<Point> parse(String geometry) {
        if (geometry == null || geometry.isBlank()) {
            throw new IllegalArgumentException("Annotation geometry is empty");
        }
        var points = new ArrayList<Point>();
        for (var value : geometry.split(";")) {
            var coordinates = value.split(",", -1);
            if (coordinates.length != 2) {
                throw new IllegalArgumentException("Annotation geometry is invalid");
            }
            var x = Double.parseDouble(coordinates[0]);
            var y = Double.parseDouble(coordinates[1]);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("Annotation geometry is invalid");
            }
            points.add(new Point(x, y));
        }
        return List.copyOf(points);
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private record Point(double x, double y) {}
}
