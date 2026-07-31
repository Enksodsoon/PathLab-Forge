package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ViewerUploadStatusTest {
    @Test
    void idleStatusReportsNoSelectedTransport() throws Exception {
        var status = ViewerUploadStatus.idle();
        var accessor = Arrays.stream(status.getClass().getMethods())
                .filter(method -> method.getName().equals("uploadMode"))
                .findFirst();

        assertTrue(accessor.isPresent(), "Viewer upload status must expose uploadMode");
        assertEquals("", accessor.orElseThrow().invoke(status));
    }
}
