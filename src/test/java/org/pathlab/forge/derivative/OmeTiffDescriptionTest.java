package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OmeTiffDescriptionTest {
    @TempDir java.nio.file.Path temporary;

    @Test
    void replacesTheClassicTiffDescriptionWithPhiFreeOmeXml() throws Exception {
        var file = temporary.resolve("minimal.tif");
        var fixture = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        fixture.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        fixture.putShort((short) 1);
        fixture.putShort((short) 270).putShort((short) 2).putInt(4).put(new byte[] {'o', 'l', 'd', 0});
        fixture.putInt(0);
        Files.write(file, fixture.array());

        OmeTiffDescription.writeMinimal(file, 37800, 34366);

        var bytes = Files.readAllBytes(file);
        var entry = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, Short.toUnsignedInt(entry.getShort(12)));
        var length = entry.getInt(14);
        var offset = entry.getInt(18);
        var xml = new String(bytes, offset, length - 1, java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(xml.startsWith("<?xml"));
        assertTrue(xml.contains("SizeX=\"37800\""));
        assertTrue(xml.contains("SizeY=\"34366\""));
        assertTrue(xml.contains("Name=\"PathLab private slide\""));
    }
}
