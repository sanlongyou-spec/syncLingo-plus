package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.LoginResponse;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P5 AuthService:登录限流接入——阻断短路、失败记账、成功清零;停用账号拒绝。
 */
class AuthServiceTest {

    private static final String IP = "203.0.113.5";

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final LoginThrottleService throttle = mock(LoginThrottleService.class);
    private final CaptchaChallengeService captcha = mock(CaptchaChallengeService.class);
    private final AuthSessionService authSessionService = mock(AuthSessionService.class);
    private final AuthService service = new AuthService(userMapper, auditService, throttle, captcha, authSessionService);

    private SiUser userWithPassword(String raw, String status) {
        SiUser u = new SiUser();
        u.setId(9L);
        u.setUsername("alice");
        u.setRole("OPERATOR");
        u.setStatus(status);
        u.setPassword(new BCryptPasswordEncoder().encode(raw));
        return u;
    }

    @Test
    void blockedThrottle_shortCircuitsBeforeAuth() {
        doThrow(BizException.of(ErrorCode.TOO_MANY_REQUESTS, "blocked"))
                .when(throttle).assertNotBlocked(IP, "alice");

        BizException ex = assertThrows(BizException.class, () -> service.login("alice", "whatever", IP, null, null));
        assertEquals(429, ex.getCode());
        verifyNoInteractions(userMapper); // 未触达认证
    }

    @Test
    void captchaRequired_shortCircuitsBeforeAuth() {
        when(throttle.requiresCaptcha(IP, "alice")).thenReturn(true);
        doThrow(BizException.of(ErrorCode.CAPTCHA_REQUIRED, "captcha"))
                .when(captcha).verify(IP, "alice", null, null);

        BizException ex = assertThrows(BizException.class, () -> service.login("alice", "whatever", IP, null, null));
        assertEquals(428, ex.getCode());
        verifyNoInteractions(userMapper);
    }

    @Test
    void badCredentials_recordsFailure() {
        when(userMapper.findByUsername("alice")).thenReturn(null);

        assertThrows(BizException.class, () -> service.login("alice", "bad", IP, null, null));
        verify(throttle).onFailedAttempt(IP, "alice");
        verify(throttle, Mockito.never()).onSuccessfulLogin(anyString(), anyString());
    }

    @Test
    void disabledAccount_recordsFailure_andRejected403() {
        when(userMapper.findByUsername("alice")).thenReturn(userWithPassword("correct", "DISABLED"));

        BizException ex = assertThrows(BizException.class, () -> service.login("alice", "correct", IP, null, null));
        assertEquals(403, ex.getCode());
        verify(throttle).onFailedAttempt(IP, "alice");
    }

    @Test
    void successfulLogin_clearsThrottle_andReturnsToken() {
        SiUser user = userWithPassword("correct", "ACTIVE");
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(authSessionService.issueForUser(user)).thenReturn(
                new AuthSessionService.IssuedAuth(new LoginResponse(9L, "access-token", "OPERATOR"), "refresh-token"));

        AuthSessionService.IssuedAuth issued = service.login("alice", "correct", IP, null, null);
        assertEquals("access-token", issued.response().getToken());
        assertEquals("OPERATOR", issued.response().getRole());
        assertEquals("refresh-token", issued.refreshToken());
        verify(throttle).onSuccessfulLogin(IP, "alice");
        verify(throttle, Mockito.never()).onFailedAttempt(eq(IP), anyString());
    }
}
