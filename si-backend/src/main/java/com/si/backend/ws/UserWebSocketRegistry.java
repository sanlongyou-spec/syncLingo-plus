package com.si.backend.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks authenticated realtime connections so credential revocation can take effect immediately.
 */
@Slf4j
@Component
public class UserWebSocketRegistry {

    private static final CloseStatus CREDENTIAL_REVOKED = new CloseStatus(4003, "credential revoked");

    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        if (userId == null || session == null) {
            return;
        }
        sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(Long userId, WebSocketSession session) {
        if (userId == null || session == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByUser.remove(userId, sessions);
        }
    }

    public int closeUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        Set<WebSocketSession> sessions = sessionsByUser.remove(userId);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        int closed = 0;
        for (WebSocketSession session : sessions) {
            if (closeQuietly(userId, session)) {
                closed++;
            }
        }
        return closed;
    }

    private boolean closeQuietly(Long userId, WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CREDENTIAL_REVOKED);
                return true;
            }
        } catch (IOException e) {
            log.debug("[UserWebSocketRegistry] failed to close ws session, userId={}, sessionId={}",
                    userId, session.getId(), e);
        }
        return false;
    }
}
