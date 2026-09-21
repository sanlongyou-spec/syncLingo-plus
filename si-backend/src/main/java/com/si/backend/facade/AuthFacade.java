package com.si.backend.facade;

import com.si.backend.dto.LoginRequest;
import com.si.backend.dto.CaptchaChallengeResponse;
import com.si.backend.service.AuthService;
import com.si.backend.service.AuthSessionService;
import com.si.backend.service.CaptchaChallengeService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.ws.UserWebSocketRegistry;
import com.si.backend.ws.ShareAudioWebSocketHandler;
import com.si.backend.ws.ShareWebSocketHandler;
import com.si.backend.dto.WsMessage;
import com.si.backend.common.Constants;
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
    private final InterpretationSessionService interpretationSessionService;
    private final RealtimeInterpretationFacade realtimeInterpretationFacade;
    private final UserWebSocketRegistry userWebSocketRegistry;
    private final ShareAudioWebSocketHandler shareAudioWebSocketHandler;
    private final ShareWebSocketHandler shareWebSocketHandler;

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

    public void logout(String refreshToken, String reason) {
        Long userId = authSessionService.logout(refreshToken);
        if (userId == null) {
            log.info("[AuthFacade] logout ignored missing session, reason={}", reason);
            return;
        }
        String normalizedReason = reason == null || reason.isBlank() ? "EXPLICIT_LOGOUT" : reason.trim();
        interpretationSessionService.getActiveSessionForUser(userId)
                .map(InterpretationSession::getSessionId)
                .ifPresent(sessionId -> {
                    log.info("[AuthFacade] logout stopping active interpretation, userId={}, sessionId={}, reason={}",
                            userId, sessionId, normalizedReason);
                    realtimeInterpretationFacade.cleanupSession(sessionId);
                    shareAudioWebSocketHandler.closeSession(sessionId);
                    WsMessage stopped = new WsMessage();
                    stopped.setType(Constants.WS_MSG_TYPE_STOPPED);
                    stopped.setSessionId(sessionId);
                    shareWebSocketHandler.broadcast(sessionId, stopped);
                });
        authSessionService.invalidateAccessTokens(userId);
        int closedConnections = userWebSocketRegistry.closeUser(userId);
        log.info("[AuthFacade] logout complete, userId={}, reason={}, closedConnections={}",
                userId, normalizedReason, closedConnections);
    }

    public CaptchaChallengeResponse issueCaptcha(String username, String clientIp) {
        log.info("[AuthFacade] issueCaptcha, username={}", username);
        return captchaChallengeService.issue(clientIp, username);
    }

}
