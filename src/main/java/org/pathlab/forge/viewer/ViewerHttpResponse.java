package org.pathlab.forge.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ViewerHttpResponse(int status, Map<String, List<String>> headers, InputStream body)
        implements AutoCloseable {
    public ViewerHttpResponse {
        headers = Map.copyOf(headers);
        Objects.requireNonNull(body);
    }

    public String header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse("");
    }

    @Override public void close() throws IOException { body.close(); }
}
