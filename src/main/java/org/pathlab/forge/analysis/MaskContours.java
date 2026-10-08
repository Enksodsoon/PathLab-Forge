package org.pathlab.forge.analysis;

import java.awt.geom.*;
import java.math.BigDecimal;
import java.util.*;

/** Bounded source-pixel even-odd contour mask. Curves are flattened at 0.01 px when composed. */
public final class MaskContours {
    public static final String PREFIX = "mask/1|";
    public static final int MAX_POINTS = 2048, MAX_RINGS = 64, MAX_TEXT = 65536;
    public static final double FLATNESS = .01;
    private MaskContours() {}
    public record Point(double x,double y) {}
    public static List<List<Point>> parse(String geometry) {
        if(geometry==null || !geometry.startsWith(PREFIX) || geometry.length()>MAX_TEXT)throw new IllegalArgumentException("Mask contour format is invalid");
        var raw=geometry.substring(PREFIX.length()).split("\\|",-1);
        if(raw.length>MAX_RINGS)throw new IllegalArgumentException("Mask has too many contours");
        var rings=new ArrayList<List<Point>>();int count=0;
        for(var ring:raw){
            var points=coordinates(ring);count+=points.size();
            if(points.stream().anyMatch(point->point.x()<0||point.y()<0))throw new IllegalArgumentException("Mask extends outside nonnegative source coordinates");
            if(points.size()<3 || count>MAX_POINTS)throw new IllegalArgumentException("Mask contour point limit or minimum is invalid");
            if(Math.abs(twiceArea(points))<1e-12)throw new IllegalArgumentException("Mask contour is degenerate");
            rings.add(points);
        }
        var result=List.copyOf(rings);
        if(area(result).isEmpty())throw new IllegalArgumentException("Mask is empty");
        return result;
    }
    public static List<Point> coordinates(String text) {
        if(text==null || text.length()>MAX_TEXT)throw new IllegalArgumentException("Mask coordinates are invalid");
        var values=text.split(";",-1);if(values.length>MAX_POINTS)throw new IllegalArgumentException("Mask has too many points");
        var points=new ArrayList<Point>();
        for(var value:values){
            if(value.length()>128 || !value.matches("-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?"))throw new IllegalArgumentException("Mask coordinates are invalid");
            var xy=value.split(",",-1);double x=Double.parseDouble(xy[0]),y=Double.parseDouble(xy[1]);
            if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>Integer.MAX_VALUE||Math.abs(y)>Integer.MAX_VALUE)throw new IllegalArgumentException("Mask coordinates exceed source limits");
            points.add(new Point(x,y));}
        return List.copyOf(points);
    }
    public static Area area(List<List<Point>> rings) {
        var path=new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for(var ring:rings){path.moveTo(ring.get(0).x(),ring.get(0).y());for(int i=1;i<ring.size();i++)path.lineTo(ring.get(i).x(),ring.get(i).y());path.closePath();}
        return new Area(path);
    }
    public static Area shape(String type,String geometry) {
        if(type.equals("roi_mask"))return area(parse(geometry));
        if(!Set.of("rectangle","ellipse","polygon","freehand","brush_add").contains(type))throw new IllegalArgumentException("A closed ROI is required; standalone subtract strokes are not ROIs");
        GeometryMeasurements.validate(type,geometry);var points=coordinates(geometry);
        if(type.equals("rectangle")||type.equals("ellipse")){
            var p=points.get(0);var q=points.get(1);double x=Math.min(p.x(),q.x()),y=Math.min(p.y(),q.y()),w=Math.abs(q.x()-p.x()),h=Math.abs(q.y()-p.y());
            return new Area(type.equals("rectangle")?new Rectangle2D.Double(x,y,w,h):new Ellipse2D.Double(x,y,w,h));
        }
        return area(List.of(points));
    }
    public static String compose(String type,String geometry,String operation,String stroke) {
        cancelled();if(!Set.of("brush_add","brush_subtract").contains(operation))throw new IllegalArgumentException("Brush operation is invalid");
        var target=shape(type,geometry);if(target.isEmpty())throw new IllegalArgumentException("Selected ROI has zero area");var original=new Area(target);var points=coordinates(stroke);
        if(points.size()<3 || points.size()>1024 || points.stream().anyMatch(point->point.x()<0||point.y()<0))throw new IllegalArgumentException("Brush requires 3 to 1024 finite source vertices");
        var brush=area(List.of(points));if(brush.isEmpty())throw new IllegalArgumentException("Brush stroke has zero area");
        if(operation.equals("brush_add"))target.add(brush);else target.subtract(brush);
        cancelled();if(target.equals(original))throw new IllegalArgumentException("Brush stroke does not change the selected ROI");return serialize(target);
    }
    public static List<List<Point>> contours(Area area) {
        var iterator=area.getPathIterator(null,FLATNESS);var rings=new ArrayList<List<Point>>();var ring=new ArrayList<Point>();var coordinates=new double[6];int count=0;
        while(!iterator.isDone()){
            cancelled();var segment=iterator.currentSegment(coordinates);
            if(segment==PathIterator.SEG_MOVETO){ring=new ArrayList<>();ring.add(new Point(coordinates[0],coordinates[1]));}
            else if(segment==PathIterator.SEG_LINETO)ring.add(new Point(coordinates[0],coordinates[1]));
            else if(segment==PathIterator.SEG_CLOSE){if(ring.size()>1&&ring.get(0).equals(ring.get(ring.size()-1)))ring.remove(ring.size()-1);count+=ring.size();if(count>MAX_POINTS||rings.size()>=MAX_RINGS)throw new IllegalArgumentException("Composed mask exceeds bounded contour complexity");rings.add(List.copyOf(ring));ring=new ArrayList<>();}
            if(count+ring.size()>MAX_POINTS)throw new IllegalArgumentException("Composed mask exceeds bounded contour complexity");
            iterator.next();
        }
        return List.copyOf(rings);
    }
    public static String serialize(Area area) {
        if(area.isEmpty())throw new IllegalArgumentException("Brush operation would remove the entire ROI");
        var rings=contours(area);var text=PREFIX+rings.stream().map(ring->ring.stream().map(p->decimal(p.x()<0&&p.x()>-1e-9?0:p.x())+","+decimal(p.y()<0&&p.y()>-1e-9?0:p.y())).collect(java.util.stream.Collectors.joining(";"))).collect(java.util.stream.Collectors.joining("|"));
        parse(text);return text;
    }
    public static Map<String,Double> measure(String geometry,double sx,double sy) {
        var rings=contours(area(parse(geometry)));double twice=0,perimeter=0;int count=0;
        for(var ring:rings){twice+=twiceArea(ring)*sx*sy;count+=ring.size();for(int i=0;i<ring.size();i++){var p=ring.get(i);var q=ring.get((i+1)%ring.size());perimeter+=Math.hypot((q.x()-p.x())*sx,(q.y()-p.y())*sy);}}
        return Map.of("pointCount",(double)count,"contourCount",(double)rings.size(),"areaPx2",Math.abs(twice)/2,"perimeterPx",perimeter);
    }
    public static String transform(String geometry,int x,int y,int width,int height,double downsample) {
        var result=area(parse(geometry));result.intersect(new Area(new Rectangle2D.Double(x,y,width,height)));
        if(result.isEmpty())return "";
        result.transform(new AffineTransform(1/downsample,0,0,1/downsample,-x/downsample,-y/downsample));return serialize(result);
    }
    private static double twiceArea(List<Point> points){double twice=0;var origin=points.get(0);for(int i=0;i<points.size();i++){var p=points.get(i);var q=points.get((i+1)%points.size());twice+=(p.x()-origin.x())*(q.y()-origin.y())-(q.x()-origin.x())*(p.y()-origin.y());}return twice;}
    private static String decimal(double value){return BigDecimal.valueOf(value==0?0:value).stripTrailingZeros().toPlainString();}
    private static void cancelled(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Brush composition cancelled");}
}
