package com.si.backend.facade;

import com.si.backend.dto.WsMessage;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AuthService;
import com.si.backend.service.AuthSessionService;
import com.si.backend.service.CaptchaChallengeService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.ws.ShareAudioWebSocketHandler;
import com.si.backend.ws.ShareWebSocketHandler;
import com.si.backend.ws.UserWebSocketRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthFacadeTest {

    private final AuthService authService = mock(AuthService.class);
    private final AuthSessionService authSessionService = mock(AuthSessionService.class);
    private final CaptchaChallengeService captchaChallengeService = mock(CaptchaChallengeService.class);
    private final InterpretationSessionService interpretationSessionService = mock(InterpretationSessionService.class);
    private final RealtimeInterpretationFacade realtimeInterpretationFacade = mock(RealtimeInterpretationFacade.class);
    private final UserWebSocketRegistry userWebSocketRegistry = mock(UserWebSocketRegistry.class);
    private final ShareAudioWebSocketHandler shareAudioWebSocketHandler = mock(ShareAudioWebSocketHandler.class);
    private final ShareWebSocketHandler shareWebSocketHandler = mock(ShareWebSocketHandler.class);
    private final AuthFacade facade = new AuthFacade(
            authService,
            authSessionService,
            captchaChallengeService,
            interpretationSessionService,
            realtimeInterpretationFacade,
            userWebSocketRegistry,
            shareAudioWebSocketHandler,
            shareWebSocketHandler
    );

    @Test
    void logout_stopsActiveInterpretationAndInvalidatesCredentials() {
        InterpretationSession active = new InterpretationSession();
        active.setSessionId("session-1");
        active.setUserId(7L);
        when(authSessionService.logout("refresh")).thenReturn(7L);
        when(interpretationSessionService.getActiveSessionForUser(7L)).thenReturn(Optional.of(active));
        when(userWebSocketRegistry.closeUser(7L)).thenReturn(1);

        facade.logout("refresh", "PAGE_UNLOAD");

        verify(realtimeInterpretationFacade).cleanupSession("session-1");
        verify(shareAudioWebSocketHandler).closeSession("session-1");
        verify(authSessionService).invalidateAccessTokens(7L);
        verify(userWebSocketRegistry).closeUser(7L);
        ArgumentCaptor<WsMessage> message = ArgumentCaptor.forClass(WsMessage.class);
        verify(shareWebSocketHandler).broadcast(org.mockito.ArgumentMatchers.eq("session-1"), message.capture());
        assertEquals("stopped", message.getValue().getType());
    }
}
