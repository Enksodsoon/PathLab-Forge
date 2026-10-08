package org.pathlab.forge.reader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PixelPlaneCompositorTest {
    @Test
    void decodesEveryScientificIntegerAndFloatingPixelClass() {
        assertArrayEquals(new double[] {255}, PixelPlaneCompositor.decode(
                new byte[] {(byte) 255}, "uint8", true));
        assertArrayEquals(new double[] {-1}, PixelPlaneCompositor.decode(
                new byte[] {(byte) 255}, "int8", true));
        assertArrayEquals(new double[] {65535}, PixelPlaneCompositor.decode(
                bytes(2).putShort((short) -1).array(), "uint16", true));
        assertArrayEquals(new double[] {-2}, PixelPlaneCompositor.decode(
                bytes(2).putShort((short) -2).array(), "int16", true));
        assertArrayEquals(new double[] {4_294_967_295d}, PixelPlaneCompositor.decode(
                bytes(4).putInt(-1).array(), "uint32", true));
        assertArrayEquals(new double[] {-3}, PixelPlaneCompositor.decode(
                bytes(4).putInt(-3).array(), "int32", true));
        assertArrayEquals(new double[] {1.25}, PixelPlaneCompositor.decode(
                bytes(4).putFloat(1.25f).array(), "float", true));
        assertArrayEquals(new double[] {2.5}, PixelPlaneCompositor.decode(
                bytes(8).putDouble(2.5).array(), "double", true));
    }

    @Test
    void computesExactMinMaxAndMeanProjections() {
        var planes = List.of(new double[] {1, 8}, new double[] {3, 4}, new double[] {2, 6});
        assertArrayEquals(new double[] {1, 4}, PixelPlaneCompositor.project(planes, AxisMode.MIN));
        assertArrayEquals(new double[] {3, 8}, PixelPlaneCompositor.project(planes, AxisMode.MAX));
        assertArrayEquals(new double[] {2, 6}, PixelPlaneCompositor.project(planes, AxisMode.MEAN));
    }

    @Test
    void compositesChannelsWithSavedRangesAndColors() {
        var rgb = PixelPlaneCompositor.composite(
                List.of(new double[] {0, 100}, new double[] {50, 100}),
                List.of(
                        new ChannelRender(0, true, "#ff0000", 0, 100),
                        new ChannelRender(1, true, "#00ff00", 0, 100)), 2);
        assertEquals(0x008000, rgb[0]);
        assertEquals(0xffff00, rgb[1]);
    }

    private static ByteBuffer bytes(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
