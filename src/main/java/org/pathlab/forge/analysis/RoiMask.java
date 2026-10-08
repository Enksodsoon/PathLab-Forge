package org.pathlab.forge.analysis;

import java.util.Arrays;
import java.util.List;
import org.pathlab.forge.conversion.RgbRegion;

public final class RoiMask {
    private final String type;
    private final List<Point> points;
    private final java.awt.geom.Area contourMask;

    public RoiMask(String type, String geometry) {
        GeometryMeasurements.validate(type, geometry);
        contourMask=type.equals("roi_mask")?MaskContours.shape(type,geometry):null;
        if (!java.util.Set.of("rectangle", "ellipse", "polygon", "freehand", "brush_add", "roi_mask").contains(type)) {
            throw new IllegalArgumentException("Analysis requires a closed ROI");
        }
        this.type = type;
        points = contourMask!=null?MaskContours.parse(geometry).stream().flatMap(java.util.Collection::stream).map(p->new Point(p.x(),p.y())).toList():Arrays.stream(geometry.split(";")).map(value -> value.split(","))
                .map(value -> new Point(Double.parseDouble(value[0]), Double.parseDouble(value[1]))).toList();
    }

    public Bounds bounds() {
        var x = (int) Math.floor(points.stream().mapToDouble(Point::x).min().orElseThrow());
        var y = (int) Math.floor(points.stream().mapToDouble(Point::y).min().orElseThrow());
        var right = (int) Math.ceil(points.stream().mapToDouble(Point::x).max().orElseThrow());
        var bottom = (int) Math.ceil(points.stream().mapToDouble(Point::y).max().orElseThrow());
        if (x < 0 || y < 0 || right <= x || bottom <= y || (long) (right - x) * (bottom - y) > 4_194_304) {
            throw new IllegalArgumentException("ROI must be inside the source and at most 4,194,304 decoded pixels");
        }
        return new Bounds(x, y, right - x, bottom - y);
    }

    public boolean contains(double x, double y) {
        if(contourMask!=null)return contourMask.contains(x,y);
        if (type.equals("rectangle")) {
            return x >= Math.min(points.get(0).x(), points.get(1).x())
                    && x <= Math.max(points.get(0).x(), points.get(1).x())
                    && y >= Math.min(points.get(0).y(), points.get(1).y())
                    && y <= Math.max(points.get(0).y(), points.get(1).y());
        }
        if (type.equals("ellipse")) {
            var rx = Math.abs(points.get(1).x() - points.get(0).x()) / 2;
            var ry = Math.abs(points.get(1).y() - points.get(0).y()) / 2;
            return Math.pow((x - (points.get(0).x() + points.get(1).x()) / 2) / rx, 2)
                    + Math.pow((y - (points.get(0).y() + points.get(1).y()) / 2) / ry, 2) <= 1;
        }
        boolean inside = false;
        for (int current = 0, previous = points.size() - 1; current < points.size(); previous = current++) {
            var a = points.get(current); var b = points.get(previous);
            if ((a.y() > y) != (b.y() > y) && x < (b.x() - a.x()) * (y - a.y()) / (b.y() - a.y()) + a.x()) inside = !inside;
        }
        return inside;
    }

    public boolean[] pixels(RgbRegion region) {
        var mask = new boolean[region.width() * region.height()];
        for (var y = 0; y < region.height(); y++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (var x = 0; x < region.width(); x++) mask[y * region.width() + x] = contains(region.x() + x + .5, region.y() + y + .5);
        }
        return mask;
    }
    public record Bounds(int x, int y, int width, int height) {}
    private record Point(double x, double y) {}
}
