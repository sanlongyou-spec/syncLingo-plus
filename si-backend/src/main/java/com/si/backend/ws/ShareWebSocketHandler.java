package com.si.backend.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.Constants;
import com.si.backend.dto.WsMessage;
import com.si.backend.service.ShareWsTicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShareWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ShareWsTicketService shareWsTicketService;
    private final Map<String, Set<WebSocketSession>> sessionSubscribers = new ConcurrentHashMap<>();
    private final Map<String, String> connectionSessionMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String ticket = UriComponentsBuilder.fromUri(session.getUri()).build()
                .getQueryParams()
                .getFirst(Constants.WS_QUERY_PARAM_TICKET);
        ShareWsTicketService.Entry entry = shareWsTicketService.consume(ticket);
        if (entry == null || entry.sessionId() == null || entry.sessionId().isBlank()) {
            log.warn("[ShareWebSocketHandler] missing/invalid share ticket, connectionId={}", session.getId());
            closeQuietly(session);
            return;
        }
        subscribe(session, entry.sessionId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("[ShareWebSocketHandler] message received, connectionId={}, payloadLen={}",
                session.getId(), message.getPayloadLength());
        try {
            WsMessage msg = objectMapper.readValue(message.getPayload(), WsMessage.class);
            if (!Constants.WS_MSG_TYPE_START.equals(msg.getType())) {
                return;
            }
            log.debug("[ShareWebSocketHandler] ignoring client message after ticket handshake, connectionId={}",
                    session.getId());
        } catch (Exception e) {
            log.warn("[ShareWebSocketHandler] subscribe failed, connectionId={}", session.getId(), e);
        }
    }

    public void broadcast(String sessionId, WsMessage msg) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (Constants.WS_MSG_TYPE_TTS_AUDIO.equals(msg.getType())) {
            log.warn("[ShareWebSocketHandler] blocked tts_audio broadcast, sessionId={}", sessionId);
            return;
        }
        Set<WebSocketSession> subscribers = sessionSubscribers.get(sessionId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        msg.setSessionId(sessionId);
        subscribers.removeIf(session -> !session.isOpen());
        subscribers.forEach(session -> sendMessage(session, msg));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = connectionSessionMap.remove(session.getId());
        if (sessionId == null) {
            return;
        }
        Set<WebSocketSession> subscribers = sessionSubscribers.get(sessionId);
        if (subscribers != null) {
            subscribers.remove(session);
            if (subscribers.isEmpty()) {
                sessionSubscribers.remove(sessionId);
            }
        }
        log.info("[ShareWebSocketHandler] unsubscribed, sessionId={}, connectionId={}, status={}",
                sessionId, session.getId(), status);
    }

    private void sendMessage(WebSocketSession session, WsMessage msg) {
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.warn("[ShareWebSocketHandler] serialize failed, connectionId={}", session.getId(), e);
            return;
        }
        synchronized (session) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException | IllegalStateException e) {
                log.warn("[ShareWebSocketHandler] send failed, connectionId={}, type={}",
                        session.getId(), msg.getType(), e);
            }
        }
    }

    private void subscribe(WebSocketSession session, String sessionId) {
        connectionSessionMap.put(session.getId(), sessionId);
        sessionSubscribers.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(session);

        WsMessage reply = new WsMessage();
        reply.setType(Constants.WS_MSG_TYPE_STARTED);
        reply.setSessionId(sessionId);
        sendMessage(session, reply);
        log.info("[ShareWebSocketHandler] subscribed, sessionId={}, connectionId={}", sessionId, session.getId());
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
            // ignore
        }
    }
}
