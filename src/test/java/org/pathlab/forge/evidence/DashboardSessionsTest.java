package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

final class DashboardSessionsTest {
    @Test
    void codeExpiresAfterSixtySeconds() {
        var sessions = new DashboardSessions();
        var now = Instant.parse("2026-08-22T00:00:00Z");
        var code = sessions.create(now);
        assertTrue(sessions.exchange(code, now.plusSeconds(61)).isEmpty());
    }
}
