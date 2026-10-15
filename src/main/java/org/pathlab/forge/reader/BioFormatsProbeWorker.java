package org.pathlab.forge.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;

/** Fixed-classpath worker used only by the contained reader probe process. */
public final class BioFormatsProbeWorker {
    private BioFormatsProbeWorker() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Exactly one source is required");
        var readerClass = Class.forName("loci.formats.ImageReader");
        var reader = readerClass.getConstructor().newInstance();
        try {
            invoke(readerClass, reader, "setFlattenedResolutions",
                    new Class<?>[] {boolean.class}, false);
            invoke(readerClass, reader, "setId", new Class<?>[] {String.class},
                    Path.of(arguments[0]).toAbsolutePath().normalize().toString());
            var seriesCount = (int) invoke(readerClass, reader, "getSeriesCount", new Class<?>[0]);
            if (seriesCount < 1) throw new IllegalStateException("Reader returned no image series");
            var multidimensional = false;
            var nativePyramid = false;
            for (var series = 0; series < seriesCount; series++) {
                invoke(readerClass, reader, "setSeries", new Class<?>[] {int.class}, series);
                multidimensional |= (int) invoke(readerClass, reader, "getSizeZ", new Class<?>[0]) > 1
                        || (int) invoke(readerClass, reader, "getSizeT", new Class<?>[0]) > 1
                        || (int) invoke(readerClass, reader, "getSizeC", new Class<?>[0]) > 3;
                nativePyramid |= (int) invoke(
                        readerClass, reader, "getResolutionCount", new Class<?>[0]) > 1;
            }
            var underlying = invoke(readerClass, reader, "getReader", new Class<?>[0]);
            var result = new LinkedHashMap<String, Object>();
            result.put("formatName", invoke(readerClass, reader, "getFormat", new Class<?>[0]));
            result.put("readerId", underlying.getClass().getSimpleName());
            result.put("usedFiles", Arrays.asList((String[]) invoke(
                    readerClass, reader, "getUsedFiles", new Class<?>[0])));
            result.put("multidimensional", multidimensional);
            result.put("nativePyramid", nativePyramid);
            System.out.println("PATHLAB_PROBE_JSON=" + new ObjectMapper().writeValueAsString(result));
        } finally {
            invoke(readerClass, reader, "close", new Class<?>[0]);
        }
    }

    private static Object invoke(
            Class<?> type, Object target, String name, Class<?>[] parameters, Object... values)
            throws Exception {
        try {
            return type.getMethod(name, parameters).invoke(target, values);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw error;
        }
    }
}
