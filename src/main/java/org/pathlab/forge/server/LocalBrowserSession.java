package org.pathlab.forge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

final class LocalBrowserSession {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private LocalBrowserSession() {}

    static String loadOrCreate(Path tokenFile) throws IOException {
        var normalized = tokenFile.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        if (Files.exists(normalized)) {
            return readValidated(normalized);
        }

        var token = randomToken();
        try {
            Files.writeString(
                    normalized,
                    token,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            restrictToCurrentUser(normalized);
            return token;
        } catch (FileAlreadyExistsException concurrentCreate) {
            return readValidated(normalized);
        }
    }

    static String randomToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String readValidated(Path tokenFile) throws IOException {
        var token = Files.readString(tokenFile, StandardCharsets.US_ASCII).trim();
        if (!TOKEN_PATTERN.matcher(token).matches()) {
            throw new IOException("The local Forge browser session file is invalid: " + tokenFile);
        }
        restrictToCurrentUser(tokenFile);
        return token;
    }

    private static void restrictToCurrentUser(Path tokenFile) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    tokenFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows protects LocalAppData with the current user's ACL.
        }
    }
}
