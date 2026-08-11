package org.pathlab.forge.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GeometryMeasurements {
    private GeometryMeasurements() {}

    public static Map<String, Double> measure(String type, String geometry) {
        var points = java.util.Arrays.stream(geometry.split(";"))
                .map(point -> point.split(",", -1))
                .map(parts -> new Point(Double.parseDouble(parts[0]), Double.parseDouble(parts[1])))
                .toList();
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Annotation geometry is empty");
        }
        var result = new LinkedHashMap<String, Double>();
        result.put("pointCount", (double) points.size());
        if ("point".equals(type)) {
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
        } else if ("ellipse".equals(type) && points.size() >= 2) {
            var radiusX = Math.abs(points.get(1).x() - points.get(0).x()) / 2;
            var radiusY = Math.abs(points.get(1).y() - points.get(0).y()) / 2;
            result.put("radiusXPx", radiusX);
            result.put("radiusYPx", radiusY);
            result.put("areaPx2", Math.PI * radiusX * radiusY);
        } else if (closed && points.size() >= 3) {
            result.put("areaPx2", polygonArea(points));
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
