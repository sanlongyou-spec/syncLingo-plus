package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process login throttling for the single-node MVP.
 *
 * <p>Two dimensions are tracked: ip+username for targeted guessing, and ip for
 * cross-account credential stuffing. There is no username-only hard lock to
 * avoid targeted account denial-of-service.
 */
@Slf4j
@Service
public class LoginThrottleService {

    static final int USER_IP_CAPTCHA_ATTEMPTS = 3;
    static final int USER_IP_FREE_ATTEMPTS = 5;
    static final int IP_CAPTCHA_ATTEMPTS = 10;
    static final int IP_FREE_ATTEMPTS = 20;
    static final long BASE_BLOCK_SECONDS = 5;
    static final long MAX_BLOCK_SECONDS = 900;
    static final long WINDOW_SECONDS = 900;
    private static final int MAX_ENTRIES = 200_000;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void assertNotBlocked(String ip, String username) {
        long now = Instant.now().getEpochSecond();
        if (isBlocked(userIpKey(ip, username), now) || isBlocked(ipKey(ip), now)) {
            log.warn("[LoginThrottleService] login throttled, ip={}, username={}", ip, username);
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS, "登录尝试过于频繁,请稍后再试");
        }
    }

    public boolean requiresCaptcha(String ip, String username) {
        return failures(userIpKey(ip, username)) >= USER_IP_CAPTCHA_ATTEMPTS
                || failures(ipKey(ip)) >= IP_CAPTCHA_ATTEMPTS;
    }

    public void onFailedAttempt(String ip, String username) {
        evictExpired();
        bump(userIpKey(ip, username), USER_IP_FREE_ATTEMPTS);
        bump(ipKey(ip), IP_FREE_ATTEMPTS);
    }

    public void onSuccessfulLogin(String ip, String username) {
        attempts.remove(userIpKey(ip, username));
    }

    private boolean isBlocked(String key, long now) {
        Attempt a = attempts.get(key);
        return a != null && now < a.blockedUntil;
    }

    private int failures(String key) {
        Attempt a = attempts.get(key);
        if (a == null || Instant.now().getEpochSecond() - a.lastSeen > WINDOW_SECONDS) {
            return 0;
        }
        return a.failures;
    }

    private void bump(String key, int freeAttempts) {
        long now = Instant.now().getEpochSecond();
        attempts.compute(key, (k, existing) -> {
            int failures = (existing == null || now - existing.lastSeen > WINDOW_SECONDS)
                    ? 1
                    : existing.failures + 1;
            long blockedUntil = 0;
            if (failures > freeAttempts) {
                long blockSeconds = Math.min(
                        BASE_BLOCK_SECONDS * (1L << Math.min(failures - freeAttempts - 1, 20)),
                        MAX_BLOCK_SECONDS);
                blockedUntil = now + blockSeconds;
            }
            return new Attempt(failures, now, blockedUntil);
        });
    }

    private void evictExpired() {
        long now = Instant.now().getEpochSecond();
        attempts.entrySet().removeIf(e -> now - e.getValue().lastSeen > WINDOW_SECONDS);
        if (attempts.size() >= MAX_ENTRIES) {
            log.warn("[LoginThrottleService] attempt store full, size={}", attempts.size());
            attempts.clear();
        }
    }

    private static String userIpKey(String ip, String username) {
        return "ua:" + safe(ip) + "|" + safe(username).toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ip) {
        return "ip:" + safe(ip);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record Attempt(int failures, long lastSeen, long blockedUntil) {
    }
}
