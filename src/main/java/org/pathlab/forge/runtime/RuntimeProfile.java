package org.pathlab.forge.runtime;

public record RuntimeProfile(
        String name,
        int maxConversionWorkers,
        long bioFormatsHeapBytes,
        int vipsConcurrency,
        long vipsCacheBytes,
        int vipsCacheFiles,
        int vipsCacheOperations,
        long idleMainJvmBytes,
        long processTreeLimitBytes,
        long workerStopAvailableBytes,
        long pauseAvailableBytes) {
    public RuntimeProfile {
        if (name == null || name.isBlank()
                || maxConversionWorkers < 1
                || bioFormatsHeapBytes < 1
                || vipsConcurrency < 1
                || vipsCacheBytes < 1
                || vipsCacheFiles < 1
                || vipsCacheOperations < 1
                || idleMainJvmBytes < 1
                || processTreeLimitBytes < 1
                || pauseAvailableBytes < 1
                || workerStopAvailableBytes <= pauseAvailableBytes) {
            throw new IllegalArgumentException("Runtime profile limits are invalid");
        }
    }

    public static RuntimeProfile target() {
        var mib = 1024L * 1024L;
        return new RuntimeProfile(
                "8gb-6core",
                5,
                640 * mib,
                4,
                768 * mib,
                128,
                100,
                512 * mib,
                5_500 * mib,
                1_250 * mib,
                768 * mib);
    }

    public boolean mayLaunchWorker(long availableSystemBytes) {
        return availableSystemBytes >= workerStopAvailableBytes;
    }

    public boolean mustPause(long availableSystemBytes) {
        return availableSystemBytes < pauseAvailableBytes;
    }
}
