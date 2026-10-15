package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class GeometryMeasurementsTest {
    @Test
    void measuresClosedAndOpenGeometryWithoutInventingPhysicalUnits() {
        var rectangle = GeometryMeasurements.measure("rectangle", "10,20;40,60");
        var ruler = GeometryMeasurements.measure("ruler", "0,0;3,4");
        var polygon = GeometryMeasurements.measure("polygon", "0,0;10,0;10,10;0,10");

        assertEquals(1_200, rectangle.get("areaPx2"));
        assertEquals(140, rectangle.get("perimeterPx"));
        assertEquals(2 * Math.PI * 5, GeometryMeasurements.measure("ellipse", "0,0;10,10")
                .get("perimeterPx"), 0.0001);
        assertEquals(5, ruler.get("lengthPx"));
        assertEquals(100, polygon.get("areaPx2"));
    }

    @Test
    void measuresAnglesDeterministically() {
        assertEquals(90, GeometryMeasurements.measure("angle", "10,0;0,0;0,10")
                .get("angleDegrees"), 0.0001);
    }

    @Test
    void measuresAnisotropicCalibrationAndRejectsIncompleteGeometry() {
        var ruler = GeometryMeasurements.measure("ruler", "0,0;3,4", 2, 3);
        assertEquals(Math.hypot(6, 12), ruler.get("lengthUm"), 0.0001);
        var rectangle = GeometryMeasurements.measure("rectangle", "0,0;3,4", 2, 3);
        assertEquals(36, rectangle.get("perimeterUm"));
        assertEquals(72, rectangle.get("areaUm2"));
        assertEquals(90, GeometryMeasurements.measure("angle", "3,0;0,0;0,4", 2, 3).get("angleDegrees"));
        assertEquals(Math.toDegrees(Math.atan(0.5)), GeometryMeasurements.measure(
                "angle", "10,10;0,0;10,0", 2, 1).get("angleDegrees"), 0.0001);
        var ellipse = GeometryMeasurements.measure("ellipse", "0,0;10,20", 2, 1);
        assertEquals(100 * Math.PI, ellipse.get("areaUm2"), 0.0001);
        assertEquals(20 * Math.PI, ellipse.get("perimeterUm"), 0.0001);
        assertFalse(GeometryMeasurements.measure("ruler", "0,0;3,4", Double.NaN, 3).containsKey("lengthUm"));
        assertThrows(IllegalArgumentException.class, () -> GeometryMeasurements.measure("polygon", "0,0;3,4"));
        assertThrows(IllegalArgumentException.class, () -> GeometryMeasurements.measure("angle", "0,0;3,4"));
        assertThrows(IllegalArgumentException.class, () -> GeometryMeasurements.measure("ellipse", "0,0;0,4"));
    }
}
