package org.pathlab.forge.viewer;

import java.io.IOException;
import java.util.Map;

public interface ViewerAuthorizedClient {
    /** Opaque account/credential namespace; empty means disconnected. Never the token itself. */
    default String connectionKey() throws IOException { return ""; }

    default ViewerHttpResponse requestBound(String expectedKey, String method, String path,
            Map<String, String> headers, byte[] body) throws IOException {
        if (expectedKey.isBlank() || !expectedKey.equals(connectionKey())) throw new IOException("Viewer account changed");
        var response = request(method, path, headers, body);
        if (!expectedKey.equals(connectionKey())) { response.close(); throw new IOException("Viewer account changed"); }
        return response;
    }

    ViewerHttpResponse request(String method, String path, Map<String, String> headers, byte[] body)
            throws IOException;
}
