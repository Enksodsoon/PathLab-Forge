package org.pathlab.forge.conversion;

public final class DiskPreflight {
    public static final long RETAINED_FREE_BYTES = 10L * 1024 * 1024 * 1024;

    private DiskPreflight() {}

    public static long requiredUsableBytes(long estimatedPeakWorkspaceBytes) {
        if (estimatedPeakWorkspaceBytes < 0) {
            throw new IllegalArgumentException("Estimated workspace must not be negative");
        }
        return Math.addExact(estimatedPeakWorkspaceBytes, RETAINED_FREE_BYTES);
    }

    public static void requireCapacity(long usableBytes, long estimatedPeakWorkspaceBytes) {
        var required = requiredUsableBytes(estimatedPeakWorkspaceBytes);
        if (usableBytes < required) {
            throw new IllegalStateException(
                    "Insufficient disk: conversion requires "
                            + required
                            + " usable bytes including the next-stage peak and 10 GiB reserve");
        }
    }
}
