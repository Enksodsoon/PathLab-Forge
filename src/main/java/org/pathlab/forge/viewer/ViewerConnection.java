package org.pathlab.forge.viewer;

import java.util.List;

public record ViewerConnection(
        boolean connected, String viewerUrl, String deviceName, List<String> scopes) {}
