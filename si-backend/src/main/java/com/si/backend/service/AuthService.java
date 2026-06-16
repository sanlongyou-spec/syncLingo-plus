package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final AuditService auditService;
    private final LoginThrottleService loginThrottleService;
    private final CaptchaChallengeService captchaChallengeService;
    private final AuthSessionService authSessionService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthSessionService.IssuedAuth login(String username, String password, String clientIp,
                                               String captchaId, String captchaAnswer) {
        log.info("[AuthService] login start, username={}, ip={}", username, clientIp);
        // P5:任一限流维度处于退避窗口则直接拒绝(429),避免暴力破解。
        loginThrottleService.assertNotBlocked(clientIp, username);
        if (loginThrottleService.requiresCaptcha(clientIp, username)) {
            captchaChallengeService.verify(clientIp, username, captchaId, captchaAnswer);
        }

        SiUser user = userMapper.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            loginThrottleService.onFailedAttempt(clientIp, username);
            log.warn("[AuthService] login failed, username={}", username);
            auditService.recordActor("ANONYMOUS", username, null, "LOGIN", "FAIL", "USER", null, "bad credentials");
            throw BizException.of(ErrorCode.AUTH_FAILED);
        }
        if ("DISABLED".equalsIgnoreCase(user.getStatus())) {
            loginThrottleService.onFailedAttempt(clientIp, username);
            log.warn("[AuthService] login rejected, account disabled, username={}", username);
            auditService.recordActor("USER", String.valueOf(user.getId()), user.getRole(), "LOGIN", "FAIL", "USER", String.valueOf(user.getId()), "account disabled");
            throw BizException.of(ErrorCode.FORBIDDEN, "账号已停用");
        }
        loginThrottleService.onSuccessfulLogin(clientIp, username);
        AuthSessionService.IssuedAuth issued = authSessionService.issueForUser(user);
        auditService.recordActor("USER", String.valueOf(user.getId()), user.getRole(), "LOGIN", "SUCCESS", "USER", String.valueOf(user.getId()), null);
        log.info("[AuthService] login end, username={}, userId={}", username, user.getId());
        return issued;
    }
}
