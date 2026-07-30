package org.pathlab.forge.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;

public final class ResourceGovernor {
    private static final ResourceGovernor SYSTEM =
            new ResourceGovernor(RuntimeProfile.target(), ResourceGovernor::availableSystemMemory);
    private final RuntimeProfile profile;
    private final LongSupplier availableBytes;

    public ResourceGovernor(RuntimeProfile profile, LongSupplier availableBytes) {
        this.profile = profile;
        this.availableBytes = availableBytes;
    }

    public static ResourceGovernor system() {
        return SYSTEM;
    }

    public Decision decide(long availableSystemBytes) {
        if (profile.mustPause(availableSystemBytes)) {
            return Decision.PAUSE;
        }
        if (!profile.mayLaunchWorker(availableSystemBytes)) {
            return Decision.WAIT;
        }
        return Decision.RUN;
    }

    public void requireConversionStart() {
        if (!Boolean.parseBoolean(
                System.getProperty("pathlab.forge.resourceGovernor.enabled", "true"))) {
            return;
        }
        var available = availableBytes.getAsLong();
        if (decide(available) != Decision.RUN) {
            throw new IllegalStateException(
                    "Conversion paused for memory safety: "
                            + available + " bytes available; 1310720000 required");
        }
    }

    public void awaitWorkerLaunch() throws IOException {
        while (decide(availableBytes.getAsLong()) != Decision.RUN) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Worker launch cancelled during memory pause", error);
            }
        }
    }

    private static long availableSystemMemory() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            return operatingSystem.getFreeMemorySize();
        }
        return Long.MAX_VALUE;
    }

    public enum Decision {
        RUN,
        WAIT,
        PAUSE
    }
}
