package org.pathlab.forge.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

public final class ResumableUploadSession {
    private static final int MAX_STREAM_BUFFER_BYTES = 1024 * 1024;
    private final Sender sender;
    private final URI uploadUri;
    private final String token;
    private final int chunkBytes;

    ResumableUploadSession(Sender sender, URI uploadUri, String token, int chunkBytes) {
        this.sender = sender;
        this.uploadUri = uploadUri;
        this.token = token;
        this.chunkBytes = chunkBytes;
    }

    HttpResponse<String> head() throws IOException {
        return sender.send(HttpRequest.newBuilder(uploadUri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build());
    }

    HttpResponse<String> patch(Path path, long offset, long remaining) throws IOException {
        var length = Math.min(chunkBytes, remaining);
        return sender.send(HttpRequest.newBuilder(uploadUri)
                .timeout(Duration.ofHours(24))
                .header("Authorization", "Bearer " + token)
                .header("Upload-Offset", Long.toString(offset))
                .header("Content-Type", "application/offset+octet-stream")
                .method("PATCH", HttpRequest.BodyPublishers.ofInputStream(
                        () -> new BoundedFileInputStream(path, offset, length)))
                .build());
    }

    long nextChunkLength(long remaining) {
        return Math.min(chunkBytes, remaining);
    }

    @FunctionalInterface
    interface Sender {
        HttpResponse<String> send(HttpRequest request) throws IOException;
    }

    private static final class BoundedFileInputStream extends InputStream {
        private final FileChannel channel;
        private long remaining;

        private BoundedFileInputStream(Path path, long offset, long length) {
            try {
                channel = FileChannel.open(path, StandardOpenOption.READ);
                channel.position(offset);
                remaining = length;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        @Override
        public int read() throws IOException {
            var single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) return -1;
            var requested = Math.toIntExact(
                    Math.min(Math.min(length, MAX_STREAM_BUFFER_BYTES), remaining));
            var read = channel.read(ByteBuffer.wrap(bytes, offset, requested));
            if (read < 0) throw new IOException("Delivery artifact ended during upload");
            remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
