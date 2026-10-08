package org.pathlab.forge.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GeometryMeasurements {
    private GeometryMeasurements() {}

    public static Map<String, Double> measure(String type, String geometry) {
        validate(type, geometry);
        var points = java.util.Arrays.stream(geometry.split(";"))
                .map(point -> point.split(",", -1))
                .map(parts -> new Point(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])))
                .toList();
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Annotation geometry is empty");
        }
        var result = new LinkedHashMap<String, Double>();
        result.put("pointCount", (double) points.size());
        if ("point".equals(type) || "text".equals(type)) {
            result.put("x", points.get(0).x());
            result.put("y", points.get(0).y());
            return Map.copyOf(result);
        }
        var closed = switch (type) {
            case "rectangle", "ellipse", "polygon", "freehand", "brush_add", "brush_subtract" -> true;
            default -> false;
        };
        var length = pathLength(points, closed);
        result.put(closed ? "perimeterPx" : "lengthPx", length);
        if ("angle".equals(type) && points.size() >= 3) {
            result.put("angleDegrees", angle(points.get(0), points.get(1), points.get(2)));
        }
        if ("rectangle".equals(type) && points.size() >= 2) {
            var width = Math.abs(points.get(1).x() - points.get(0).x());
            var height = Math.abs(points.get(1).y() - points.get(0).y());
            result.put("widthPx", width);
            result.put("heightPx", height);
            result.put("areaPx2", width * height);
            result.put("perimeterPx", 2 * (width + height));
        } else if ("ellipse".equals(type) && points.size() >= 2) {
            var radiusX = Math.abs(points.get(1).x() - points.get(0).x()) / 2;
            var radiusY = Math.abs(points.get(1).y() - points.get(0).y()) / 2;
            result.put("radiusXPx", radiusX);
            result.put("radiusYPx", radiusY);
            result.put("areaPx2", Math.PI * radiusX * radiusY);
            var h = Math.pow((radiusX - radiusY) / (radiusX + radiusY), 2);
            result.put("perimeterPx", Math.PI * (radiusX + radiusY)
                    * (1 + 3 * h / (10 + Math.sqrt(4 - 3 * h))));
        } else if (closed && points.size() >= 3) {
            result.put("areaPx2", polygonArea(points));
        }
        return Map.copyOf(result);
    }

    public static void validate(String type, String geometry) {
        if (geometry == null || geometry.length() > 65_536 || !geometry.matches(
                "-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?(?:;-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?)*")) {
            throw new IllegalArgumentException("Annotation geometry is invalid");
        }
        var count = geometry.split(";").length;
        var valid = switch (type) {
            case "point", "text" -> count == 1;
            case "rectangle", "ellipse", "ruler", "line", "measure" -> count == 2;
            case "angle" -> count == 3;
            case "polyline" -> count >= 2;
            case "polygon", "freehand", "brush_add", "brush_subtract" -> count >= 3;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Annotation point count is invalid for tool");
        for (var coordinate : geometry.split("[;,]")) {
            if (!Double.isFinite(Double.parseDouble(coordinate))) {
                throw new IllegalArgumentException("Annotation coordinate is not finite");
            }
        }
        if (type.equals("angle")) {
            var coordinates = java.util.Arrays.stream(geometry.split("[;,]"))
                    .mapToDouble(Double::parseDouble).toArray();
            if ((coordinates[0] == coordinates[2] && coordinates[1] == coordinates[3])
                    || (coordinates[2] == coordinates[4] && coordinates[3] == coordinates[5])) {
                throw new IllegalArgumentException("Angle arms have zero length");
            }
        }
        if (type.equals("rectangle") || type.equals("ellipse")) {
            var parts = geometry.split("[;,]");
            if (Double.parseDouble(parts[0]) == Double.parseDouble(parts[2])
                    || Double.parseDouble(parts[1]) == Double.parseDouble(parts[3])) {
                throw new IllegalArgumentException("Annotation bounds have zero area");
            }
        }
    }

    public static Map<String, Double> measure(
            String type, String geometry, double pixelSizeXMicrons, double pixelSizeYMicrons) {
        var result = new LinkedHashMap<>(measure(type, geometry));
        if (!Double.isFinite(pixelSizeXMicrons) || pixelSizeXMicrons <= 0
                || !Double.isFinite(pixelSizeYMicrons) || pixelSizeYMicrons <= 0) return Map.copyOf(result);
        var scaled = java.util.Arrays.stream(geometry.split(";"))
                .map(point -> point.split(","))
                .map(parts -> java.math.BigDecimal.valueOf(Double.parseDouble(parts[0]) * pixelSizeXMicrons)
                        .toPlainString() + "," + java.math.BigDecimal.valueOf(
                                Double.parseDouble(parts[1]) * pixelSizeYMicrons).toPlainString())
                .collect(java.util.stream.Collectors.joining(";"));
        for (var entry : measure(type, scaled).entrySet()) {
            if (entry.getKey().endsWith("Px2")) result.put(entry.getKey().replace("Px2", "Um2"), entry.getValue());
            else if (entry.getKey().endsWith("Px")) result.put(entry.getKey().replace("Px", "Um"), entry.getValue());
            else if (entry.getKey().equals("angleDegrees")) result.put("angleDegrees", entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static double pathLength(java.util.List<Point> points, boolean closed) {
        double total = 0;
        for (var index = 1; index < points.size(); index++) {
            total += distance(points.get(index - 1), points.get(index));
        }
        if (closed && points.size() > 2) {
            total += distance(points.get(points.size() - 1), points.get(0));
        }
        return total;
    }

    private static double polygonArea(java.util.List<Point> points) {
        double twice = 0;
        for (var index = 0; index < points.size(); index++) {
            var next = (index + 1) % points.size();
            twice += points.get(index).x() * points.get(next).y()
                    - points.get(next).x() * points.get(index).y();
        }
        return Math.abs(twice) / 2;
    }

    private static double angle(Point first, Point vertex, Point last) {
        var ax = first.x() - vertex.x();
        var ay = first.y() - vertex.y();
        var bx = last.x() - vertex.x();
        var by = last.y() - vertex.y();
        var denominator = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (denominator == 0) {
            return 0;
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ax * bx + ay * by) / denominator))));
    }

    private static double distance(Point first, Point second) {
        return Math.hypot(second.x() - first.x(), second.y() - first.y());
    }

    private record Point(double x, double y) {}
}
