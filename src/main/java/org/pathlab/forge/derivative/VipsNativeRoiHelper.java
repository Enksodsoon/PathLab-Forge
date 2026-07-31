package org.pathlab.forge.derivative;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolated native helper that keeps one libvips source image open while writing quality ROIs.
 *
 * <p>This runs in a child JVM so a broken native codec cannot terminate Forge.
 */
public final class VipsNativeRoiHelper {
    private static final int HEADER_ARGUMENTS = 4;
    private static final int ROI_ARGUMENTS = 5;

    private VipsNativeRoiHelper() {}

    public static void main(String[] arguments) {
        try {
            extract(arguments);
        } catch (Throwable error) {
            System.err.println("Native ROI extraction failed: " + error.getMessage());
            System.exit(2);
        }
    }

    private static void extract(String[] arguments) {
        if (arguments.length < HEADER_ARGUMENTS + ROI_ARGUMENTS
                || (arguments.length - HEADER_ARGUMENTS) % ROI_ARGUMENTS != 0) {
            throw new IllegalArgumentException("Native ROI arguments are invalid");
        }
        var libraryDirectory = Path.of(arguments[0]);
        var sourcePath = arguments[1];
        var outputRoot = Path.of(arguments[2]);
        var workers = Math.max(1, Integer.parseInt(arguments[3]));
        var vips = Native.load(
                libraryDirectory.resolve("libvips-42.dll").toString(), VipsApi.class);
        var gobject = Native.load(
                libraryDirectory.resolve("libgobject-2.0-0.dll").toString(), GObjectApi.class);
        if (vips.vips_init("pathlab-quality-roi") != 0) {
            throw new IllegalStateException("libvips initialization failed");
        }
        vips.vips_concurrency_set(1);
        var source = vips.vips_image_new_from_file(sourcePath, (Object) null);
        if (source == null) {
            throw new IllegalStateException("libvips could not open the staging image");
        }
        try {
            var completed = new AtomicInteger();
            var executor = Executors.newFixedThreadPool(workers);
            try {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (var index = HEADER_ARGUMENTS;
                        index < arguments.length;
                        index += ROI_ARGUMENTS) {
                    var argumentIndex = index;
                    futures.add(executor.submit(() -> {
                        var output = outputRoot.resolve(arguments[argumentIndex]);
                        var result = new PointerByReference();
                        var status = vips.vips_crop(
                                source,
                                result,
                                Integer.parseInt(arguments[argumentIndex + 1]),
                                Integer.parseInt(arguments[argumentIndex + 2]),
                                Integer.parseInt(arguments[argumentIndex + 3]),
                                Integer.parseInt(arguments[argumentIndex + 4]),
                                (Object) null);
                        var image = result.getValue();
                        if (status != 0 || image == null) {
                            throw new IllegalStateException("libvips could not crop quality ROI");
                        }
                        try {
                            if (vips.vips_image_write_to_file(
                                            image,
                                            output.toAbsolutePath().toString(),
                                            (Object) null)
                                    != 0) {
                                throw new IllegalStateException(
                                        "libvips could not write quality ROI");
                            }
                        } finally {
                            gobject.g_object_unref(image);
                        }
                        synchronized (completed) {
                            System.out.println("PATHLAB_ROI=" + completed.incrementAndGet());
                        }
                    }));
                }
                for (var future : futures) {
                    try {
                        future.get();
                    } catch (Exception error) {
                        throw new IllegalStateException("Parallel native ROI extraction failed", error);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        } finally {
            gobject.g_object_unref(source);
        }
    }

    private interface VipsApi extends Library {
        int vips_init(String programName);

        void vips_concurrency_set(int concurrency);

        Pointer vips_image_new_from_file(String filename, Object... options);

        int vips_crop(
                Pointer input,
                PointerByReference output,
                int left,
                int top,
                int width,
                int height,
                Object... options);

        int vips_image_write_to_file(Pointer image, String filename, Object... options);
    }

    private interface GObjectApi extends Library {
        void g_object_unref(Pointer object);
    }
}
