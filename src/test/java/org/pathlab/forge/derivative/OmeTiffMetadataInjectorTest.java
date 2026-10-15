package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OmeTiffMetadataInjectorTest {
    @TempDir Path temporary;

    @Test
    void appendsOmeXmlAndReplacementClassicIfdWithoutRewritingPixelData() throws Exception {
        var file = temporary.resolve("classic.ome.tif");
        writeClassic(file);
        var originalBytes = Files.size(file);

        OmeTiffMetadataInjector.inject(file, 2220, 2967, "a".repeat(64), "PATHOLOGY_STANDARD");

        assertTrue(Files.size(file) > originalBytes);
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var header = read(channel, 0, 8).order(ByteOrder.LITTLE_ENDIAN);
            var replacement = Integer.toUnsignedLong(header.getInt(4));
            assertNotEquals(8, replacement);
            var description = description(channel, replacement, false, ByteOrder.LITTLE_ENDIAN);
            assertTrue(description.contains("<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\""));
            assertTrue(description.contains("SizeX=\"2220\" SizeY=\"2967\""));
            assertTrue(description.contains("ViewRevision=" + "a".repeat(64)));
        }
    }

    @Test
    void supportsBigTiffFirstIfdLayout() throws Exception {
        var file = temporary.resolve("big.ome.tif");
        writeBig(file);

        OmeTiffMetadataInjector.inject(file, 64, 32, "b".repeat(64), "DISPLAY_COMPOSITE");

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var header = read(channel, 0, 16).order(ByteOrder.LITTLE_ENDIAN);
            var replacement = header.getLong(8);
            assertNotEquals(16, replacement);
            var description = description(channel, replacement, true, ByteOrder.LITTLE_ENDIAN);
            assertTrue(description.contains("SizeX=\"64\" SizeY=\"32\""));
            assertTrue(description.contains("RenderProfile=DISPLAY_COMPOSITE"));
        }
    }

    private static String description(FileChannel channel, long offset, boolean big, ByteOrder order)
            throws Exception {
        var countBytes = big ? 8 : 2;
        var entryBytes = big ? 20 : 12;
        var countBuffer = read(channel, offset, countBytes).order(order);
        var count = big ? countBuffer.getLong() : Short.toUnsignedLong(countBuffer.getShort());
        for (var index = 0; index < count; index++) {
            var entry = read(channel, offset + countBytes + index * entryBytes, entryBytes).order(order);
            var tag = Short.toUnsignedInt(entry.getShort());
            entry.getShort();
            var length = big ? entry.getLong() : Integer.toUnsignedLong(entry.getInt());
            var value = big ? entry.getLong() : Integer.toUnsignedLong(entry.getInt());
            if (tag == 270) {
                var bytes = read(channel, value, Math.toIntExact(length - 1));
                return java.nio.charset.StandardCharsets.UTF_8.decode(bytes).toString();
            }
        }
        throw new AssertionError("ImageDescription was not added");
    }

    private static void writeClassic(Path file) throws Exception {
        var bytes = ByteBuffer.allocate(38).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        bytes.putShort((short) 2);
        entryClassic(bytes, 256, 4, 1, 2220);
        entryClassic(bytes, 257, 4, 1, 2967);
        bytes.putInt(0);
        Files.write(file, bytes.array());
    }

    private static void writeBig(Path file) throws Exception {
        var bytes = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) 'I').put((byte) 'I').putShort((short) 43).putShort((short) 8)
                .putShort((short) 0).putLong(16).putLong(2);
        entryBig(bytes, 256, 16, 1, 64);
        entryBig(bytes, 257, 16, 1, 32);
        bytes.putLong(0);
        Files.write(file, bytes.array());
    }

    private static void entryClassic(ByteBuffer bytes, int tag, int type, long count, long value) {
        bytes.putShort((short) tag).putShort((short) type).putInt((int) count).putInt((int) value);
    }

    private static void entryBig(ByteBuffer bytes, int tag, int type, long count, long value) {
        bytes.putShort((short) tag).putShort((short) type).putLong(count).putLong(value);
    }

    private static ByteBuffer read(FileChannel channel, long offset, int count) throws Exception {
        var buffer = ByteBuffer.allocate(count);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) throw new AssertionError("Unexpected EOF");
        }
        return buffer.flip();
    }
}
