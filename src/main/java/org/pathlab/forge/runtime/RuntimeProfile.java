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
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

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
        return new RuntimeProfile(
                "8gb-6core",
                5,
                640 * MIB,
                5,
                1_024 * MIB,
                192,
                128,
                512 * MIB,
                5_500 * MIB,
                1_250 * MIB,
                768 * MIB);
    }

    public static RuntimeProfile system() {
        var processors = configuredLogicalProcessors();
        var memoryBytes = Long.getLong(
                "pathlab.forge.runtime.memoryBytes",
                totalPhysicalMemory());
        return adaptive(processors, memoryBytes);
    }

    public static int configuredLogicalProcessors() {
        return Integer.getInteger(
                "pathlab.forge.runtime.processors",
                Runtime.getRuntime().availableProcessors());
    }

    /**
     * Returns a throughput-oriented worker count instead of treating every SMT
     * thread as an independent image decoder.
     */
    public static int effectiveCpuParallelism(int logicalProcessors) {
        if (logicalProcessors < 1) {
            throw new IllegalArgumentException("Detected CPU capacity is invalid");
        }
        if (logicalProcessors <= 6) {
            return Math.max(1, logicalProcessors - 1);
        }
        return Math.max(5, logicalProcessors / 2);
    }

    public static RuntimeProfile adaptive(int logicalProcessors, long physicalMemoryBytes) {
        if (logicalProcessors < 1 || physicalMemoryBytes < GIB) {
            throw new IllegalArgumentException("Detected hardware capacity is invalid");
        }
        var cpuWorkers = Math.min(
                12,
                Math.min(
                        Math.max(1, logicalProcessors - 1),
                        Math.max(1, 4 + logicalProcessors / 3)));
        var memoryWorkers = Math.max(
                1,
                Math.toIntExact(Math.min(
                        12,
                        Math.max(1, (physicalMemoryBytes - 3 * GIB) / (768 * MIB)))));
        var workers = Math.min(cpuWorkers, memoryWorkers);
        var vipsConcurrency = Math.min(16, Math.max(1, logicalProcessors - 1));
        var vipsCacheBytes = Math.min(
                4 * GIB,
                Math.max(1_024 * MIB, physicalMemoryBytes / 12));
        var processTreeLimit = Math.min(
                physicalMemoryBytes - Math.min(2 * GIB, physicalMemoryBytes / 4),
                physicalMemoryBytes * 7 / 10);
        var pauseBytes = Math.max(768 * MIB, physicalMemoryBytes / 32);
        var workerStopBytes = Math.max(1_250 * MIB, pauseBytes + 384 * MIB);
        var roundedMemoryGiB = Math.max(1, Math.round((double) physicalMemoryBytes / GIB));
        return new RuntimeProfile(
                logicalProcessors == 6 && roundedMemoryGiB == 8
                        ? "8gb-6core"
                        : "adaptive-%dc-%dgb".formatted(logicalProcessors, roundedMemoryGiB),
                workers,
                640 * MIB,
                vipsConcurrency,
                vipsCacheBytes,
                Math.min(512, 192 + Math.max(0, workers - 5) * 32),
                Math.min(384, 128 + Math.max(0, vipsConcurrency - 5) * 16),
                512 * MIB,
                Math.max(2 * GIB, processTreeLimit),
                workerStopBytes,
                pauseBytes);
    }

    private static long totalPhysicalMemory() {
        var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            return operatingSystem.getTotalMemorySize();
        }
        return 8 * GIB;
    }

    public boolean mayLaunchWorker(long availableSystemBytes) {
        return availableSystemBytes >= workerStopAvailableBytes;
    }

    public boolean mustPause(long availableSystemBytes) {
        return availableSystemBytes < pauseAvailableBytes;
    }
}
