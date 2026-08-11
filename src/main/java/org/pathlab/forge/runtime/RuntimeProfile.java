package org.pathlab.forge.runtime;

public record RuntimeProfile(
        String name,
        int maxConversionWorkers,
        long bioFormatsHeapBytes,
        int vipsConcurrency,
        long vipsCacheBytes,
        int vipsCacheFiles,
        int vipsCacheOperations,
        int quPathProcessors,
        long quPathHeapBytes,
        int previewReaderSessions,
        long previewReaderCacheBytes,
        int bioFormatsDirectReaders,
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
                || quPathProcessors < 1
                || quPathHeapBytes < 1
                || previewReaderSessions < 1
                || previewReaderCacheBytes < 1
                || bioFormatsDirectReaders < 1
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
                2,
                640 * MIB,
                2,
                256 * MIB,
                96,
                64,
                2,
                2 * GIB,
                1,
                128 * MIB,
                1,
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
        var reservedCpu = Math.max(1, logicalProcessors - 1);
        int workers;
        int quPathProcessors;
        long quPathHeapBytes;
        int vipsConcurrency;
        long vipsCacheBytes;
        int previewSessions;
        long previewCacheBytes;
        int directReaders;
        if (physicalMemoryBytes < 8 * GIB) {
            workers = 1;
            quPathProcessors = 1;
            quPathHeapBytes = GIB;
            vipsConcurrency = 1;
            vipsCacheBytes = 128 * MIB;
            previewSessions = 1;
            previewCacheBytes = 96 * MIB;
            directReaders = 1;
        } else if (physicalMemoryBytes < 16 * GIB) {
            workers = 2;
            quPathProcessors = 2;
            quPathHeapBytes = 2 * GIB;
            vipsConcurrency = 2;
            vipsCacheBytes = 256 * MIB;
            previewSessions = 1;
            previewCacheBytes = 128 * MIB;
            directReaders = 1;
        } else if (physicalMemoryBytes < 32 * GIB) {
            workers = 4;
            quPathProcessors = 4;
            quPathHeapBytes = 3 * GIB;
            vipsConcurrency = 4;
            vipsCacheBytes = 512 * MIB;
            previewSessions = 2;
            previewCacheBytes = 256 * MIB;
            directReaders = 2;
        } else {
            workers = 6;
            quPathProcessors = 6;
            quPathHeapBytes = 4 * GIB;
            vipsConcurrency = 6;
            vipsCacheBytes = GIB;
            previewSessions = 2;
            previewCacheBytes = 256 * MIB;
            directReaders = 2;
        }
        workers = Math.min(workers, reservedCpu);
        quPathProcessors = Math.min(quPathProcessors, reservedCpu);
        vipsConcurrency = Math.min(vipsConcurrency, reservedCpu);
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
                Math.min(256, 64 + workers * 16),
                Math.min(192, 48 + vipsConcurrency * 8),
                quPathProcessors,
                quPathHeapBytes,
                previewSessions,
                previewCacheBytes,
                directReaders,
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
