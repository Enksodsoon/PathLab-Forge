package org.pathlab.forge.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ForgeCommandLine(
        boolean serve,
        boolean noBrowser,
        Path dataRoot,
        Path importSource,
        Path benchmarkSource,
        Path performanceReport,
        boolean readerSelfTest,
        List<Path> selfTestSources,
        Integer port) {
    public ForgeCommandLine {
        selfTestSources = List.copyOf(selfTestSources);
    }
    public static ForgeCommandLine parse(String[] args) {
        Objects.requireNonNull(args, "args");
        boolean serve = false;
        boolean noBrowser = false;
        Path dataRoot = null;
        Path importSource = null;
        Path benchmarkSource = null;
        Path performanceReport = null;
        boolean readerSelfTest = false;
        var selfTestSources = new ArrayList<Path>();
        Integer port = null;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--serve" -> serve = true;
                case "--no-browser" -> noBrowser = true;
                case "--data-root" -> dataRoot = Path.of(value(args, ++index, "--data-root"));
                case "--import" -> importSource = Path.of(value(args, ++index, "--import"));
                case "--benchmark" ->
                        benchmarkSource = Path.of(value(args, ++index, "--benchmark"));
                case "--performance-report" ->
                        performanceReport = Path.of(value(args, ++index, "--performance-report"));
                case "--reader-self-test" -> readerSelfTest = true;
                case "--self-test-source" ->
                        selfTestSources.add(Path.of(value(args, ++index, "--self-test-source")));
                case "--port" -> port = port(value(args, ++index, "--port"));
                default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
            }
        }
        return new ForgeCommandLine(
                serve, noBrowser, dataRoot, importSource, benchmarkSource, performanceReport,
                readerSelfTest, selfTestSources, port);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a path");
        }
        return args[index];
    }

    private static int port(String value) {
        try {
            var parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 65_535) throw new IllegalArgumentException("Port is out of range");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Port must be an integer", error);
        }
    }
}
