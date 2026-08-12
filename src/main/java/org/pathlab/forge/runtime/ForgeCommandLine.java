package org.pathlab.forge.runtime;

import java.nio.file.Path;
import java.util.Objects;

public record ForgeCommandLine(
        boolean serve,
        boolean noBrowser,
        Path dataRoot,
        Path importSource,
        Path benchmarkSource,
        Path performanceReport) {
    public static ForgeCommandLine parse(String[] args) {
        Objects.requireNonNull(args, "args");
        boolean serve = false;
        boolean noBrowser = false;
        Path dataRoot = null;
        Path importSource = null;
        Path benchmarkSource = null;
        Path performanceReport = null;
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
                default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
            }
        }
        return new ForgeCommandLine(
                serve, noBrowser, dataRoot, importSource, benchmarkSource, performanceReport);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a path");
        }
        return args[index];
    }
}
