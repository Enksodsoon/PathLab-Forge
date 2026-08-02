package org.pathlab.forge.derivative;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Writes a minimal, PHI-free OME description without copying the large TIFF. */
final class OmeTiffDescription {
    private static final int IMAGE_DESCRIPTION_TAG = 270;
    private static final int ASCII_TYPE = 2;

    private OmeTiffDescription() {}

    static void writeMinimal(Path path, int width, int height) throws IOException {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("OME description geometry is invalid");
        }
        var xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\">"
                + "<Image ID=\"Image:0\" Name=\"PathLab private slide\">"
                + "<Pixels ID=\"Pixels:0\" DimensionOrder=\"XYZCT\" Type=\"uint8\""
                + " SizeX=\"" + width + "\" SizeY=\"" + height
                + "\" SizeZ=\"1\" SizeC=\"3\" SizeT=\"1\" Interleaved=\"true\">"
                + "<Channel ID=\"Channel:0:0\" SamplesPerPixel=\"3\"/>"
                + "<TiffData IFD=\"0\" PlaneCount=\"1\"/>"
                + "</Pixels></Image></OME>").getBytes(StandardCharsets.US_ASCII);
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var header = read(channel, 0, 16);
            var order = byteOrder(header.get(0), header.get(1));
            header.order(order);
            var magic = Short.toUnsignedInt(header.getShort(2));
            var bigTiff = magic == 43;
            if (!bigTiff && magic != 42) {
                throw new IOException("OME output is not a TIFF file");
            }
            long ifdOffset;
            if (bigTiff) {
                if (Short.toUnsignedInt(header.getShort(4)) != 8) {
                    throw new IOException("Unsupported BigTIFF offset width");
                }
                ifdOffset = header.getLong(8);
            } else {
                ifdOffset = Integer.toUnsignedLong(header.getInt(4));
            }
            var countWidth = bigTiff ? 8 : 2;
            var entryWidth = bigTiff ? 20 : 12;
            var countBuffer = read(channel, ifdOffset, countWidth).order(order);
            long entryCount = bigTiff
                    ? countBuffer.getLong(0)
                    : Short.toUnsignedLong(countBuffer.getShort(0));
            if (entryCount < 1 || entryCount > 65_535) {
                throw new IOException("OME TIFF directory is invalid");
            }
            long entryOffset = -1;
            for (long index = 0; index < entryCount; index++) {
                var candidate = ifdOffset + countWidth + index * entryWidth;
                var tag = read(channel, candidate, 2).order(order);
                if (Short.toUnsignedInt(tag.getShort(0)) == IMAGE_DESCRIPTION_TAG) {
                    entryOffset = candidate;
                    break;
                }
            }
            if (entryOffset < 0) {
                throw new IOException("OME TIFF image-description tag is missing");
            }
            var payloadOffset = channel.size();
            if (!bigTiff && payloadOffset > 0xffff_ffffL) {
                throw new IOException("Classic TIFF description offset exceeds 32 bits");
            }
            channel.position(payloadOffset);
            writeFully(channel, ByteBuffer.wrap(xml));
            writeFully(channel, ByteBuffer.wrap(new byte[] {0}));

            var entry = read(channel, entryOffset, entryWidth).order(order);
            entry.putShort(2, (short) ASCII_TYPE);
            if (bigTiff) {
                entry.putLong(4, xml.length + 1L);
                entry.putLong(12, payloadOffset);
            } else {
                entry.putInt(4, xml.length + 1);
                entry.putInt(8, (int) payloadOffset);
            }
            entry.position(0);
            channel.position(entryOffset);
            writeFully(channel, entry);
            channel.force(true);
        }
    }

    private static ByteOrder byteOrder(byte first, byte second) throws IOException {
        if (first == 'I' && second == 'I') return ByteOrder.LITTLE_ENDIAN;
        if (first == 'M' && second == 'M') return ByteOrder.BIG_ENDIAN;
        throw new IOException("OME output has an invalid TIFF byte order");
    }

    private static ByteBuffer read(FileChannel channel, long offset, int length) throws IOException {
        var buffer = ByteBuffer.allocate(length);
        channel.position(offset);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("OME TIFF ended unexpectedly");
        }
        return buffer.flip();
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }
}
