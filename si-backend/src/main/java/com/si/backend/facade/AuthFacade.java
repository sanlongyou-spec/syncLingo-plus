package com.si.backend.facade;

import com.si.backend.dto.LoginRequest;
import com.si.backend.dto.CaptchaChallengeResponse;
import com.si.backend.service.AuthService;
import com.si.backend.service.AuthSessionService;
import com.si.backend.service.CaptchaChallengeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFacade {

    private final AuthService authService;
    private final AuthSessionService authSessionService;
    private final CaptchaChallengeService captchaChallengeService;

    public AuthSessionService.IssuedAuth login(LoginRequest request, String clientIp) {
        log.info("[AuthFacade] login start, username={}", request.getUsername());
        AuthSessionService.IssuedAuth issued = authService.login(
                request.getUsername(),
                request.getPassword(),
                clientIp,
                request.getCaptchaId(),
                request.getCaptchaAnswer()
        );
        log.info("[AuthFacade] login end, username={}, userId={}", request.getUsername(), issued.response().getUserId());
        return issued;
    }

    public AuthSessionService.IssuedAuth refresh(String refreshToken) {
        return authSessionService.refresh(refreshToken);
    }

    public void logout(String refreshToken) {
        authSessionService.logout(refreshToken);
    }

    public CaptchaChallengeResponse issueCaptcha(String username, String clientIp) {
        log.info("[AuthFacade] issueCaptcha, username={}", username);
        return captchaChallengeService.issue(clientIp, username);
    }

}
