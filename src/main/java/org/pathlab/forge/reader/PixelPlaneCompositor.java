package org.pathlab.forge.reader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;

public final class PixelPlaneCompositor {
    private PixelPlaneCompositor() {}

    public static double[] decode(byte[] bytes, String pixelType, boolean littleEndian) {
        var type = pixelType.toLowerCase(Locale.ROOT);
        var size = switch (type) {
            case "uint8", "int8" -> 1;
            case "uint16", "int16" -> 2;
            case "uint32", "int32", "float", "float32" -> 4;
            case "double", "float64" -> 8;
            default -> throw new IllegalArgumentException("Unsupported pixel type: " + pixelType);
        };
        if (bytes.length % size != 0) throw new IllegalArgumentException("Pixel buffer is truncated");
        var buffer = ByteBuffer.wrap(bytes).order(
                littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        var result = new double[bytes.length / size];
        for (var index = 0; index < result.length; index++) {
            result[index] = switch (type) {
                case "uint8" -> buffer.get() & 0xff;
                case "int8" -> buffer.get();
                case "uint16" -> buffer.getShort() & 0xffff;
                case "int16" -> buffer.getShort();
                case "uint32" -> Integer.toUnsignedLong(buffer.getInt());
                case "int32" -> buffer.getInt();
                case "float", "float32" -> buffer.getFloat();
                case "double", "float64" -> buffer.getDouble();
                default -> throw new IllegalStateException();
            };
        }
        return result;
    }

    public static double[] project(List<double[]> planes, AxisMode mode) {
        if (planes.isEmpty() || mode == AxisMode.SLICE) {
            throw new IllegalArgumentException("Projection needs planes and a projection mode");
        }
        var length = planes.get(0).length;
        if (planes.stream().anyMatch(plane -> plane.length != length)) {
            throw new IllegalArgumentException("Projection planes differ in size");
        }
        var output = planes.get(0).clone();
        for (var planeIndex = 1; planeIndex < planes.size(); planeIndex++) {
            var plane = planes.get(planeIndex);
            for (var pixel = 0; pixel < length; pixel++) {
                output[pixel] = switch (mode) {
                    case MIN -> Math.min(output[pixel], plane[pixel]);
                    case MAX -> Math.max(output[pixel], plane[pixel]);
                    case MEAN -> output[pixel] + plane[pixel];
                    case SLICE -> throw new IllegalArgumentException("SLICE is not a projection");
                };
            }
        }
        if (mode == AxisMode.MEAN) {
            for (var pixel = 0; pixel < length; pixel++) output[pixel] /= planes.size();
        }
        return output;
    }

    public static int[] composite(
            List<double[]> planes, List<ChannelRender> channels, int pixels) {
        if (planes.size() != channels.size() || pixels < 1
                || planes.stream().anyMatch(plane -> plane.length != pixels)) {
            throw new IllegalArgumentException("Composite planes do not match channel metadata");
        }
        var output = new int[pixels];
        for (var index = 0; index < planes.size(); index++) {
            var channel = channels.get(index);
            if (!channel.enabled()) continue;
            var color = Integer.parseInt(channel.color().substring(1), 16);
            var red = color >>> 16;
            var green = (color >>> 8) & 0xff;
            var blue = color & 0xff;
            var scale = channel.maximum() - channel.minimum();
            for (var pixel = 0; pixel < pixels; pixel++) {
                var normalized = Math.max(0, Math.min(1,
                        (planes.get(index)[pixel] - channel.minimum()) / scale));
                var current = output[pixel];
                var r = Math.min(255, (current >>> 16) + (int) Math.round(red * normalized));
                var g = Math.min(255, ((current >>> 8) & 0xff)
                        + (int) Math.round(green * normalized));
                var b = Math.min(255, (current & 0xff) + (int) Math.round(blue * normalized));
                output[pixel] = (r << 16) | (g << 8) | b;
            }
        }
        return output;
    }
}
