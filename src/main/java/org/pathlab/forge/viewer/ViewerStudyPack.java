package org.pathlab.forge.viewer;

public record ViewerStudyPack(
        String id,
        String packKey,
        int version,
        String checksum,
        boolean masteryEligible,
        String status) {}
