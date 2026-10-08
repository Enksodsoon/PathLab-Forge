package org.pathlab.forge.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.util.Map;

/** The owning desktop reads this private pipe; never copy readiness to application logs. */
public final class DesktopStartup {
    private DesktopStartup() {}

    public static void failed(PrintStream pipe, String code) throws IOException {
        if (!java.util.Set.of("DATA_LOCKED", "DATA_DENIED", "SERVICE_UNAVAILABLE").contains(code)) {
            throw new IllegalArgumentException("Unknown desktop startup failure");
        }
        pipe.println("PATHLAB_FORGE_FAILED " + new ObjectMapper().writeValueAsString(Map.of("protocol", 1, "code", code)));
        pipe.flush();
    }

    public static void ready(PrintStream pipe, URI origin, URI launchUri, String desktopSecret) throws IOException {
        if (!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())
                || origin.getPort() <= 0 || !origin.resolve("/").equals(launchUri.resolve("/"))
                || desktopSecret == null || !desktopSecret.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("Desktop service must use one loopback origin");
        }
        pipe.println("PATHLAB_FORGE_READY " + new ObjectMapper().writeValueAsString(Map.of(
                "protocol", 1, "origin", origin.resolve("/").toString(),
                "launchUrl", launchUri.toString(), "desktopSecret", desktopSecret)));
        pipe.flush();
    }

    public static Thread watchOwner(InputStream pipe, Runnable stop) {
        var watcher = new Thread(() -> {
            try {
                while (pipe.read() != -1) { /* owner keeps this pipe open */ }
            } catch (IOException ignored) {
                // A broken owner pipe is also a shutdown request.
            } finally {
                stop.run();
            }
        }, "pathlab-forge-desktop-owner");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }
}
