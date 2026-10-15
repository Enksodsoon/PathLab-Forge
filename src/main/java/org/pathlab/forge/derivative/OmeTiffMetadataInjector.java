package org.pathlab.forge.derivative;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Adds selected-view OME-XML by replacing only the first IFD pointer. */
public final class OmeTiffMetadataInjector {
    private static final int IMAGE_DESCRIPTION = 270;
    private static final int MAX_IFD_ENTRIES = 4_096;

    private OmeTiffMetadataInjector() {}

    public static void inject(Path file, int width, int height, String viewRevision, String profile)
            throws IOException {
        if (width < 1 || height < 1 || viewRevision == null
                || !viewRevision.matches("[0-9a-f]{64}") || profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("OME selected-view metadata is invalid");
        }
        var xml = omeXml(width, height, viewRevision, profile).getBytes(StandardCharsets.UTF_8);
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var header = read(channel, 0, 16);
            var order = byteOrder(header);
            header.order(order);
            var magic = Short.toUnsignedInt(header.getShort(2));
            var big = magic == 43;
            if (!big && magic != 42) throw new IOException("OME metadata target is not TIFF");
            if (big && (Short.toUnsignedInt(header.getShort(4)) != 8 || header.getShort(6) != 0)) {
                throw new IOException("BigTIFF header is invalid");
            }
            var firstIfd = big ? header.getLong(8) : Integer.toUnsignedLong(header.getInt(4));
            var countBytes = big ? 8 : 2;
            var entryBytes = big ? 20 : 12;
            var nextBytes = big ? 8 : 4;
            var countBuffer = read(channel, firstIfd, countBytes).order(order);
            var count = big ? countBuffer.getLong() : Short.toUnsignedLong(countBuffer.getShort());
            if (count < 1 || count > MAX_IFD_ENTRIES) throw new IOException("TIFF IFD entry count is invalid");
            var original = read(channel, firstIfd + countBytes,
                    Math.toIntExact(Math.addExact(Math.multiplyExact(count, entryBytes), nextBytes))).order(order);
            var entries = new ArrayList<byte[]>();
            long nextIfd = 0;
            for (var index = 0; index < count; index++) {
                var raw = new byte[entryBytes];
                original.get(raw);
                var tag = Short.toUnsignedInt(ByteBuffer.wrap(raw).order(order).getShort());
                if (tag != IMAGE_DESCRIPTION) entries.add(raw);
            }
            nextIfd = big ? original.getLong() : Integer.toUnsignedLong(original.getInt());
            var xmlOffset = channel.size();
            write(channel, xmlOffset, ByteBuffer.wrap(xml));
            write(channel, xmlOffset + xml.length, ByteBuffer.wrap(new byte[] {0}));
            var newIfd = align(xmlOffset + xml.length + 1, big ? 8 : 2);
            if (!big && (xmlOffset > 0xffff_ffffL || newIfd > 0xffff_ffffL)) {
                throw new IOException("Classic TIFF metadata offset exceeds 32-bit range");
            }
            if (newIfd > xmlOffset + xml.length + 1) {
                write(channel, xmlOffset + xml.length + 1,
                        ByteBuffer.allocate(Math.toIntExact(newIfd - xmlOffset - xml.length - 1)));
            }
            entries.add(descriptionEntry(big, order, xml.length + 1L, xmlOffset));
            entries.sort(Comparator.comparingInt(raw ->
                    Short.toUnsignedInt(ByteBuffer.wrap(raw).order(order).getShort())));
            var outputSize = Math.addExact(countBytes,
                    Math.addExact(Math.multiplyExact(entries.size(), entryBytes), nextBytes));
            var replacement = ByteBuffer.allocate(Math.toIntExact(outputSize)).order(order);
            if (big) replacement.putLong(entries.size()); else replacement.putShort((short) entries.size());
            entries.forEach(replacement::put);
            if (big) replacement.putLong(nextIfd); else replacement.putInt((int) nextIfd);
            write(channel, newIfd, replacement.flip());
            channel.force(true);
            var pointer = ByteBuffer.allocate(big ? 8 : 4).order(order);
            if (big) pointer.putLong(newIfd); else pointer.putInt((int) newIfd);
            write(channel, big ? 8 : 4, pointer.flip());
            channel.force(true);
        }
    }

    private static byte[] descriptionEntry(
            boolean big, ByteOrder order, long count, long offset) {
        var entry = ByteBuffer.allocate(big ? 20 : 12).order(order);
        entry.putShort((short) IMAGE_DESCRIPTION).putShort((short) 2);
        if (big) entry.putLong(count).putLong(offset);
        else entry.putInt((int) count).putInt((int) offset);
        return entry.array();
    }

    private static String omeXml(int width, int height, String viewRevision, String profile) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<OME xmlns=\"http://www.openmicroscopy.org/Schemas/OME/2016-06\""
                + " Creator=\"PathLab Forge\" UUID=\"urn:uuid:" + uuid(viewRevision) + "\">"
                + "<Image ID=\"Image:0\" Name=\"PathLab selected view\">"
                + "<Description>ViewRevision=" + viewRevision + ";RenderProfile="
                + xml(profile) + "</Description>"
                + "<Pixels ID=\"Pixels:0\" DimensionOrder=\"XYCZT\" Type=\"uint8\""
                + " SizeX=\"" + width + "\" SizeY=\"" + height
                + "\" SizeC=\"3\" SizeZ=\"1\" SizeT=\"1\" Interleaved=\"true\" BigEndian=\"false\">"
                + "<Channel ID=\"Channel:0:0\" SamplesPerPixel=\"3\"/>"
                + "<TiffData IFD=\"0\" PlaneCount=\"1\"/>"
                + "</Pixels></Image></OME>";
    }

    private static String uuid(String hash) {
        return hash.substring(0, 8) + "-" + hash.substring(8, 12) + "-"
                + hash.substring(12, 16) + "-" + hash.substring(16, 20) + "-"
                + hash.substring(20, 32);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static ByteOrder byteOrder(ByteBuffer header) throws IOException {
        if (header.get(0) == 'I' && header.get(1) == 'I') return ByteOrder.LITTLE_ENDIAN;
        if (header.get(0) == 'M' && header.get(1) == 'M') return ByteOrder.BIG_ENDIAN;
        throw new IOException("TIFF byte order is invalid");
    }

    private static long align(long value, int boundary) {
        return Math.addExact(value, (boundary - value % boundary) % boundary);
    }

    private static ByteBuffer read(FileChannel channel, long offset, int bytes) throws IOException {
        if (offset < 0 || bytes < 0 || offset > channel.size() - bytes) {
            throw new IOException("TIFF metadata offset is outside the file");
        }
        var buffer = ByteBuffer.allocate(bytes);
        while (buffer.hasRemaining()) {
            var read = channel.read(buffer, offset + buffer.position());
            if (read < 0) throw new IOException("Unexpected end of TIFF metadata");
        }
        return buffer.flip();
    }

    private static void write(FileChannel channel, long offset, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer, offset + buffer.position());
    }
}
