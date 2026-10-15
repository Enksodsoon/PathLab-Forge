package org.pathlab.forge.analysis;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
final class RoiMaskTest {
    @Test void brushAndFreehandUseActualRegionRatherThanBoundingBox() {
        for (var type : java.util.List.of("polygon", "brush_add", "freehand")) {
            var mask = new RoiMask(type, "0,0;10,0;0,10");
            assertTrue(mask.contains(1,1));
            assertFalse(mask.contains(9,9));
        }
        assertThrows(IllegalArgumentException.class,()->new RoiMask("brush_subtract","0,0;10,0;0,10"));
        assertFalse(new RoiMask("ellipse", "0,0;10,10").contains(1,1));
    }
}
