package com.si.backend.security;

import com.si.backend.common.BizException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies anonymous endpoint rate limits are fail-closed per key.
 */
class AnonymousRequestRateLimiterTest {

    @Test
    void requestBeyondLimit_throws429() {
        AnonymousRequestRateLimiter limiter = new AnonymousRequestRateLimiter(
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC)
        );

        assertDoesNotThrow(() -> limiter.requireAllowed("active", "127.0.0.1:5", 2, 60));
        assertDoesNotThrow(() -> limiter.requireAllowed("active", "127.0.0.1:5", 2, 60));
        BizException error = assertThrows(
                BizException.class,
                () -> limiter.requireAllowed("active", "127.0.0.1:5", 2, 60)
        );

        assertEquals(429, error.getCode());
    }

    @Test
    void differentTargetKey_hasIndependentBudget() {
        AnonymousRequestRateLimiter limiter = new AnonymousRequestRateLimiter(
                Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC)
        );

        limiter.requireAllowed("active", "127.0.0.1:5", 1, 60);

        assertDoesNotThrow(() -> limiter.requireAllowed("active", "127.0.0.1:6", 1, 60));
    }
}
