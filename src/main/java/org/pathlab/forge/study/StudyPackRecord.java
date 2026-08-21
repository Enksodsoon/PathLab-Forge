package org.pathlab.forge.study;

import java.nio.file.Path;

public record StudyPackRecord(
        String packKey, int version, String title, String checksum, String reviewedAt, Path path) {}
