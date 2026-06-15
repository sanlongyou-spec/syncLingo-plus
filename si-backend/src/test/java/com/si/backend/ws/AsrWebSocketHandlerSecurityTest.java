package com.si.backend.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.facade.RealtimeInterpretationFacade;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.service.ResourceOwnershipPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the ASR WebSocket connection-to-session ownership state machine.
 */
class AsrWebSocketHandlerSecurityTest {

    private final RealtimeInterpretationFacade realtimeFacade = mock(RealtimeInterpretationFacade.class);
    private final ShareWebSocketHandler shareWebSocketHandler = mock(ShareWebSocketHandler.class);
    private final ShareAudioWebSocketHandler shareAudioWebSocketHandler = mock(ShareAudioWebSocketHandler.class);
    private final ResourceOwnershipPolicy ownershipPolicy = mock(ResourceOwnershipPolicy.class);
    private final AsrWebSocketHandler handler = new AsrWebSocketHandler(
            new ObjectMapper(),
            realtimeFacade,
            shareWebSocketHandler,
            shareAudioWebSocketHandler,
            ownershipPolicy
    );
    private WebSocketSession session;

    @AfterEach
    void closeConnection() throws Exception {
        if (session != null) {
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        }
    }

    @Test
    void audioBeforeStart_isRejectedAndConnectionClosed() throws Exception {
        session = session("ws-1", 5L);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, message("audio", "s1", "\"audioBase64\":\"AA==\""));

        verify(realtimeFacade, never()).pushAudio(any(), any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void startOwnedSession_bindsAndStartsRealtimePipeline() throws Exception {
        session = session("ws-2", 5L);
        InterpretationSession owned = new InterpretationSession();
        owned.setSessionId("s2");
        owned.setUserId(5L);
        when(ownershipPolicy.requireOwnedSession(new AuthenticatedActor(5L), "s2")).thenReturn(owned);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, message(
                "start",
                "s2",
                "\"sourceLang\":\"zh-CN\",\"targetLang\":\"id-ID\""
        ));

        verify(realtimeFacade).startInterpretation(
                eq("s2"),
                eq("zh-CN"),
                eq("id-ID"),
                isNull(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void startOtherUsersSession_isRejected() throws Exception {
        session = session("ws-3", 5L);
        when(ownershipPolicy.requireOwnedSession(new AuthenticatedActor(5L), "other"))
                .thenThrow(BizException.of(ErrorCode.NOT_FOUND));
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, message(
                "start",
                "other",
                "\"sourceLang\":\"zh-CN\",\"targetLang\":\"id-ID\""
        ));

        verify(realtimeFacade, never()).startInterpretation(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void boundConnectionCannotSwitchSession() throws Exception {
        session = session("ws-4", 5L);
        InterpretationSession owned = new InterpretationSession();
        owned.setSessionId("s4");
        owned.setUserId(5L);
        when(ownershipPolicy.requireOwnedSession(new AuthenticatedActor(5L), "s4")).thenReturn(owned);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, message(
                "start",
                "s4",
                "\"sourceLang\":\"zh-CN\",\"targetLang\":\"id-ID\""
        ));

        handler.handleTextMessage(session, message("audio", "other", "\"audioBase64\":\"AA==\""));

        verify(realtimeFacade, never()).pushAudio(eq("other"), any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    private WebSocketSession session(String id, Long userId) {
        WebSocketSession webSocketSession = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(JwtHandshakeInterceptor.ATTRIBUTE_AUTHENTICATED_USER_ID, userId);
        when(webSocketSession.getId()).thenReturn(id);
        when(webSocketSession.getAttributes()).thenReturn(attributes);
        when(webSocketSession.isOpen()).thenReturn(true);
        return webSocketSession;
    }

    private TextMessage message(String type, String sessionId, String additionalJson) {
        return new TextMessage("{\"type\":\"" + type + "\",\"sessionId\":\"" + sessionId + "\","
                + additionalJson + "}");
    }
}
