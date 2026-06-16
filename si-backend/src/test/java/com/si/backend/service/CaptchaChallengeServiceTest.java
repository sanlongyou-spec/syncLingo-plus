package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.dto.CaptchaChallengeResponse;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptchaChallengeServiceTest {

    private static final Pattern QUESTION = Pattern.compile("(\\d+) \\+ (\\d+) = \\?");

    private final CaptchaChallengeService service = new CaptchaChallengeService();

    @Test
    void issuedCaptcha_canBeVerifiedOnce() {
        CaptchaChallengeResponse challenge = service.issue("203.0.113.9", "Alice");

        assertFalse(challenge.getCaptchaId().isBlank());
        assertEquals(CaptchaChallengeService.CAPTCHA_TTL_SECONDS, challenge.getExpiresInSeconds());
        String answer = answer(challenge);

        assertDoesNotThrow(() -> service.verify("203.0.113.9", "alice", challenge.getCaptchaId(), answer));
        BizException ex = assertThrows(BizException.class,
                () -> service.verify("203.0.113.9", "alice", challenge.getCaptchaId(), answer));
        assertEquals(428, ex.getCode());
    }

    @Test
    void wrongAnswerOrBinding_isRejected() {
        CaptchaChallengeResponse challenge = service.issue("203.0.113.9", "alice");

        assertEquals(428, assertThrows(BizException.class,
                () -> service.verify("203.0.113.9", "alice", challenge.getCaptchaId(), "0")).getCode());

        CaptchaChallengeResponse second = service.issue("203.0.113.9", "alice");
        assertEquals(428, assertThrows(BizException.class,
                () -> service.verify("198.51.100.9", "alice", second.getCaptchaId(), answer(second))).getCode());
    }

    @Test
    void missingCaptcha_isRejected() {
        assertEquals(428, assertThrows(BizException.class,
                () -> service.verify("203.0.113.9", "alice", null, null)).getCode());
    }

    private String answer(CaptchaChallengeResponse challenge) {
        Matcher matcher = QUESTION.matcher(challenge.getQuestion());
        if (!matcher.matches()) {
            throw new AssertionError("unexpected question: " + challenge.getQuestion());
        }
        return String.valueOf(Integer.parseInt(matcher.group(1)) + Integer.parseInt(matcher.group(2)));
    }
}
