package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class GeometryMeasurementsTest {
    @Test
    void measuresClosedAndOpenGeometryWithoutInventingPhysicalUnits() {
        var rectangle = GeometryMeasurements.measure("rectangle", "10,20;40,60");
        var ruler = GeometryMeasurements.measure("ruler", "0,0;3,4");
        var polygon = GeometryMeasurements.measure("polygon", "0,0;10,0;10,10;0,10");

        assertEquals(1_200, rectangle.get("areaPx2"));
        assertEquals(5, ruler.get("lengthPx"));
        assertEquals(100, polygon.get("areaPx2"));
    }

    @Test
    void measuresAnglesDeterministically() {
        assertEquals(90, GeometryMeasurements.measure("angle", "10,0;0,0;0,10")
                .get("angleDegrees"), 0.0001);
    }
}
