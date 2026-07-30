package org.pathlab.forge.runtime;

import com.sun.jna.IntegerType;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ChildProcessContainment implements AutoCloseable {
    private static final ChildProcessContainment GLOBAL = createGlobal();
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final WindowsJob windowsJob;

    public ChildProcessContainment() {
        windowsJob = WindowsJob.create(RuntimeProfile.target().processTreeLimitBytes());
    }

    public static ChildProcessContainment global() {
        return GLOBAL;
    }

    private static ChildProcessContainment createGlobal() {
        var containment = new ChildProcessContainment();
        Runtime.getRuntime().addShutdownHook(new Thread(
                containment::close, "pathlab-child-process-containment"));
        return containment;
    }

    public Process register(Process process) {
        windowsJob.assign(process.pid());
        processes.add(process);
        process.onExit().thenRun(() -> processes.remove(process));
        return process;
    }

    @Override
    public void close() {
        for (var process : processes.toArray(Process[]::new)) {
            terminateTree(process);
            processes.remove(process);
        }
        windowsJob.close();
    }

    private static void terminateTree(Process process) {
        process.descendants()
                .sorted(java.util.Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static final class WindowsJob implements AutoCloseable {
        private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
        private static final int JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
        private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
        private static final int PROCESS_SET_QUOTA = 0x0100;
        private static final int PROCESS_TERMINATE = 0x0001;
        private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        private final Kernel32 kernel32;
        private final Pointer handle;

        private WindowsJob(Kernel32 kernel32, Pointer handle) {
            this.kernel32 = kernel32;
            this.handle = handle;
        }

        static WindowsJob create(long memoryLimit) {
            if (!System.getProperty("os.name", "").startsWith("Windows")) {
                return new WindowsJob(null, null);
            }
            try {
                var kernel = Native.load(
                        "kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);
                var job = kernel.CreateJobObjectW(Pointer.NULL, new WString("PathLabForge"));
                if (job == null) {
                    return new WindowsJob(null, null);
                }
                var limits = new JobObjectExtendedLimitInformation();
                limits.basicLimitInformation.limitFlags =
                        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_JOB_MEMORY;
                limits.jobMemoryLimit = new SizeT(memoryLimit);
                limits.write();
                if (!kernel.SetInformationJobObject(
                        job,
                        JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                        limits.getPointer(),
                        limits.size())) {
                    kernel.CloseHandle(job);
                    return new WindowsJob(null, null);
                }
                return new WindowsJob(kernel, job);
            } catch (RuntimeException | LinkageError ignored) {
                return new WindowsJob(null, null);
            }
        }

        void assign(long processId) {
            if (handle == null) {
                return;
            }
            var process = kernel32.OpenProcess(
                    PROCESS_SET_QUOTA | PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION,
                    false,
                    Math.toIntExact(processId));
            if (process == null) {
                return;
            }
            try {
                kernel32.AssignProcessToJobObject(handle, process);
            } finally {
                kernel32.CloseHandle(process);
            }
        }

        @Override
        public void close() {
            if (handle != null) {
                kernel32.CloseHandle(handle);
            }
        }
    }

    private interface Kernel32 extends StdCallLibrary {
        Pointer CreateJobObjectW(Pointer securityAttributes, WString name);

        boolean SetInformationJobObject(
                Pointer job, int informationClass, Pointer information, int length);

        Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean AssignProcessToJobObject(Pointer job, Pointer process);

        boolean CloseHandle(Pointer handle);
    }

    public static final class SizeT extends IntegerType {
        private static final long serialVersionUID = 1L;

        public SizeT() {
            this(0);
        }

        public SizeT(long value) {
            super(Native.SIZE_T_SIZE, value, true);
        }
    }

    @Structure.FieldOrder({
        "perProcessUserTimeLimit",
        "perJobUserTimeLimit",
        "limitFlags",
        "minimumWorkingSetSize",
        "maximumWorkingSetSize",
        "activeProcessLimit",
        "affinity",
        "priorityClass",
        "schedulingClass"
    })
    public static final class BasicLimitInformation extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public SizeT minimumWorkingSetSize;
        public SizeT maximumWorkingSetSize;
        public int activeProcessLimit;
        public SizeT affinity;
        public int priorityClass;
        public int schedulingClass;
    }

    @Structure.FieldOrder({
        "readOperationCount",
        "writeOperationCount",
        "otherOperationCount",
        "readTransferCount",
        "writeTransferCount",
        "otherTransferCount"
    })
    public static final class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;
    }

    @Structure.FieldOrder({
        "basicLimitInformation",
        "ioInfo",
        "processMemoryLimit",
        "jobMemoryLimit",
        "peakProcessMemoryUsed",
        "peakJobMemoryUsed"
    })
    public static final class JobObjectExtendedLimitInformation extends Structure {
        public BasicLimitInformation basicLimitInformation = new BasicLimitInformation();
        public IoCounters ioInfo = new IoCounters();
        public SizeT processMemoryLimit = new SizeT();
        public SizeT jobMemoryLimit = new SizeT();
        public SizeT peakProcessMemoryUsed = new SizeT();
        public SizeT peakJobMemoryUsed = new SizeT();
    }
}
