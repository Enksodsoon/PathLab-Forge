package org.pathlab.forge.conversion;

public final class DiskPreflight {
    public static final long RETAINED_FREE_BYTES = 25L * 1024 * 1024 * 1024;

    private DiskPreflight() {}

    public static long requiredUsableBytes(long estimatedPeakWorkspaceBytes) {
        if (estimatedPeakWorkspaceBytes < 0) {
            throw new IllegalArgumentException("Estimated workspace must not be negative");
        }
        var withHeadroom = Math.multiplyExact(estimatedPeakWorkspaceBytes, 6) / 5;
        return Math.addExact(withHeadroom, RETAINED_FREE_BYTES);
    }

    public static void requireCapacity(long usableBytes, long estimatedPeakWorkspaceBytes) {
        var required = requiredUsableBytes(estimatedPeakWorkspaceBytes);
        if (usableBytes < required) {
            throw new IllegalStateException(
                    "Insufficient disk: conversion requires "
                            + required
                            + " usable bytes including 20% headroom and 25 GiB retained free");
        }
    }
}
