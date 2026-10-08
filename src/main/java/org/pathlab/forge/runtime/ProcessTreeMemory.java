package org.pathlab.forge.runtime;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.stream.Stream;

public final class ProcessTreeMemory {
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final Psapi PSAPI = loadPsapi();
    private static final Kernel32 KERNEL32 = loadKernel32();

    private ProcessTreeMemory() {}

    public record Snapshot(long residentBytes, int measuredProcesses, int totalProcesses) {
        public boolean complete() { return measuredProcesses == totalProcesses; }
    }

    public static Snapshot snapshot() {
        var current = ProcessHandle.current();
        try (Stream<ProcessHandle> handles = Stream.concat(Stream.of(current), current.descendants())) {
            long total = 0;
            int measured = 0;
            var processes = handles.toList();
            for (var process : processes) {
                var rss = workingSetBytes(process);
                if (rss >= 0) { total += rss; measured++; }
            }
            return new Snapshot(measured == 0 ? -1 : total, measured, processes.size());
        }
    }

    /** -1 means complete process-tree RSS could not be measured. */
    public static long workingSetBytes() {
        var reading = snapshot();
        return reading.complete() ? reading.residentBytes() : -1;
    }

    private static long workingSetBytes(ProcessHandle process) {
        if (PSAPI == null || KERNEL32 == null) {
            return macResidentBytes(process.pid());
        }
        var handle = KERNEL32.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION, false, (int) process.pid());
        if (handle == null) {
            return -1;
        }
        try {
            var counters = new ProcessMemoryCounters();
            counters.cb = counters.size();
            return PSAPI.GetProcessMemoryInfo(
                            handle, counters, counters.size())
                    ? counters.workingSetSize.longValue()
                    : -1;
        } finally {
            KERNEL32.CloseHandle(handle);
        }
    }

    private static long macResidentBytes(long pid) {
        if (!System.getProperty("os.name", "").startsWith("Mac")) return -1;
        try (var usage = new com.sun.jna.Memory(96)) {
            usage.clear();
            return MacProc.INSTANCE.proc_pid_rusage(Math.toIntExact(pid), 0, usage) == 0
                    ? usage.getLong(64) : -1;
        } catch (RuntimeException | LinkageError error) { return -1; }
    }

    private interface MacProc extends com.sun.jna.Library {
        MacProc INSTANCE = Native.load("proc", MacProc.class);
        int proc_pid_rusage(int pid, int flavor, Pointer buffer);
    }

    private static Psapi loadPsapi() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return null;
        }
        try {
            return Native.load("psapi", Psapi.class, W32APIOptions.DEFAULT_OPTIONS);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Kernel32 loadKernel32() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return null;
        }
        try {
            return Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private interface Kernel32 extends StdCallLibrary {
        Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean CloseHandle(Pointer handle);
    }

    private interface Psapi extends StdCallLibrary {
        boolean GetProcessMemoryInfo(Pointer process, ProcessMemoryCounters counters, int size);
    }

    @Structure.FieldOrder({
        "cb",
        "pageFaultCount",
        "peakWorkingSetSize",
        "workingSetSize",
        "quotaPeakPagedPoolUsage",
        "quotaPagedPoolUsage",
        "quotaPeakNonPagedPoolUsage",
        "quotaNonPagedPoolUsage",
        "pagefileUsage",
        "peakPagefileUsage",
        "privateUsage"
    })
    public static final class ProcessMemoryCounters extends Structure {
        public int cb;
        public int pageFaultCount;
        public ChildProcessContainment.SizeT peakWorkingSetSize =
                new ChildProcessContainment.SizeT();
        public ChildProcessContainment.SizeT workingSetSize =
                new ChildProcessContainment.SizeT();
        public ChildProcessContainment.SizeT quotaPeakPagedPoolUsage =
                new ChildProcessContainment.SizeT();
        public ChildProcessContainment.SizeT quotaPagedPoolUsage =
                new ChildProcessContainment.SizeT();
        public ChildProcessContainment.SizeT quotaPeakNonPagedPoolUsage =
                new ChildProcessContainment.SizeT();
        public ChildProcessContainment.SizeT quotaNonPagedPoolUsage =
                new ChildProcessContainment.SizeT();
        public ChildProcessContainment.SizeT pagefileUsage =
                new ChildProcessContainment.SizeT();
        public ChildProcessContainment.SizeT peakPagefileUsage =
                new ChildProcessContainment.SizeT();
        public ChildProcessContainment.SizeT privateUsage =
                new ChildProcessContainment.SizeT();
    }
}
