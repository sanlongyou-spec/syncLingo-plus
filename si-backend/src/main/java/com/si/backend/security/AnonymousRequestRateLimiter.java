package com.si.backend.security;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small single-instance fixed-window limiter for anonymous compatibility endpoints.
 */
@Slf4j
@Component
public class AnonymousRequestRateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final Clock clock;

    public AnonymousRequestRateLimiter() {
        this(Clock.systemUTC());
    }

    AnonymousRequestRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void requireAllowed(String scope, String key, int limit, long windowSeconds) {
        long currentWindow = clock.instant().getEpochSecond() / windowSeconds;
        String counterKey = scope + ":" + key;
        WindowCounter counter = counters.compute(counterKey, (ignored, existing) -> {
            if (existing == null || existing.windowId() != currentWindow) {
                return new WindowCounter(currentWindow, 1);
            }
            return new WindowCounter(currentWindow, existing.count() + 1);
        });
        if (counters.size() > CLEANUP_THRESHOLD) {
            counters.entrySet().removeIf(entry -> entry.getValue().windowId() < currentWindow);
        }
        if (counter.count() > limit) {
            log.warn("[AnonymousRequestRateLimiter] request limited, scope={}, key={}", scope, key);
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS, "Too many requests");
        }
    }

    private record WindowCounter(long windowId, int count) {
    }
}
