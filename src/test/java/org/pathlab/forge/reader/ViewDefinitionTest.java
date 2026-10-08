package org.pathlab.forge.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ViewDefinitionTest {
    @Test
    void revisionChangesWhenPlaneOrChannelRenderingChanges() {
        var base = view(AxisSelection.slice(1), List.of(new ChannelRender(0, true, "#ff0000", 10, 200)));
        var otherPlane = view(AxisSelection.slice(2), base.channels());
        var otherRange = view(AxisSelection.slice(1), List.of(new ChannelRender(0, true, "#ff0000", 20, 200)));

        assertNotEquals(base.revision(), otherPlane.revision());
        assertNotEquals(base.revision(), otherRange.revision());
        assertEquals(base.revision(), view(AxisSelection.slice(1), base.channels()).revision());
    }

    @Test
    void refusesProjectionOnBothAxes() {
        assertThrows(IllegalArgumentException.class, () -> new ViewDefinition(
                0,
                new AxisSelection(AxisMode.MEAN, 0, 4),
                new AxisSelection(AxisMode.MAX, 0, 3),
                List.of(new ChannelRender(0, true, "#ffffff", 0, 255)),
                RenderProfile.DISPLAY_COMPOSITE));
    }

    private static ViewDefinition view(AxisSelection z, List<ChannelRender> channels) {
        return new ViewDefinition(
                0,
                z,
                AxisSelection.slice(0),
                channels,
                RenderProfile.DISPLAY_COMPOSITE);
    }
}
