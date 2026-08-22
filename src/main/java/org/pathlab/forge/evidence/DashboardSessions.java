package org.pathlab.forge.evidence;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory, boot-scoped dashboard authentication. Nothing survives a service restart. */
final class DashboardSessions {
    private static final Duration CODE_LIFETIME = Duration.ofSeconds(60);
    private static final Duration SESSION_LIFETIME = Duration.ofHours(8);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> codes = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();

    synchronized String create(Instant now) {
        prune(now);
        var code = random(32);
        codes.put(code, now.plus(CODE_LIFETIME));
        return code;
    }

    synchronized Optional<Session> exchange(String code, Instant now) {
        prune(now);
        var expiry = codes.remove(code);
        if (expiry == null || expiry.isBefore(now)) return Optional.empty();
        var token = random(32);
        var session = new Session(token, random(32), now.plus(SESSION_LIFETIME));
        sessions.put(token, session);
        return Optional.of(session);
    }

    synchronized Optional<Session> find(String token, Instant now) {
        prune(now);
        return Optional.ofNullable(sessions.get(token));
    }

    private void prune(Instant now) {
        codes.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String random(int bytes) {
        var value = new byte[bytes]; random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    record Session(String token, String csrfToken, Instant expiresAt) { }
}
