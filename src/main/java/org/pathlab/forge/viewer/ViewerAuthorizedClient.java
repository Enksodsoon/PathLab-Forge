package org.pathlab.forge.viewer;

import java.io.IOException;
import java.util.Map;

public interface ViewerAuthorizedClient {
    ViewerHttpResponse request(String method, String path, Map<String, String> headers, byte[] body)
            throws IOException;
}
