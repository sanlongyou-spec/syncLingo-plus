package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.CaptchaChallengeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CaptchaChallengeService {

    static final long CAPTCHA_TTL_SECONDS = 300;
    private static final int MIN_TERM = 10;
    private static final int TERM_RANGE = 40;
    private static final int MAX_CHALLENGES = 100_000;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Challenge> challenges = new ConcurrentHashMap<>();

    public CaptchaChallengeResponse issue(String ip, String username) {
        evictExpired();
        int left = MIN_TERM + random.nextInt(TERM_RANGE);
        int right = MIN_TERM + random.nextInt(TERM_RANGE);
        String id = UUID.randomUUID().toString();
        long expiresAt = Instant.now().getEpochSecond() + CAPTCHA_TTL_SECONDS;
        challenges.put(id, new Challenge(safe(ip), normalizeUsername(username), String.valueOf(left + right), expiresAt));
        log.info("[CaptchaChallengeService] issued captcha, username={}, ip={}", username, ip);
        return new CaptchaChallengeResponse(id, left + " + " + right + " = ?", CAPTCHA_TTL_SECONDS);
    }

    public void verify(String ip, String username, String captchaId, String captchaAnswer) {
        long now = Instant.now().getEpochSecond();
        if (captchaId == null || captchaId.isBlank() || captchaAnswer == null || captchaAnswer.isBlank()) {
            log.warn("[CaptchaChallengeService] captcha missing, username={}, ip={}", username, ip);
            throw required();
        }
        Challenge challenge = challenges.remove(captchaId);
        if (challenge == null
                || now > challenge.expiresAt
                || !challenge.ip.equals(safe(ip))
                || !challenge.username.equals(normalizeUsername(username))
                || !challenge.answer.equals(captchaAnswer.trim())) {
            log.warn("[CaptchaChallengeService] captcha rejected, username={}, ip={}", username, ip);
            throw required();
        }
    }

    private void evictExpired() {
        long now = Instant.now().getEpochSecond();
        challenges.entrySet().removeIf(e -> now > e.getValue().expiresAt);
        if (challenges.size() >= MAX_CHALLENGES) {
            log.warn("[CaptchaChallengeService] challenge store full, size={}", challenges.size());
            challenges.clear();
        }
    }

    private BizException required() {
        return BizException.of(ErrorCode.CAPTCHA_REQUIRED, "请输入验证码后重试");
    }

    private static String normalizeUsername(String username) {
        return safe(username).toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record Challenge(String ip, String username, String answer, long expiresAt) {
    }
}
