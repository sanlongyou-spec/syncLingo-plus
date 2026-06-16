package com.si.backend.ws;

import com.si.backend.service.WsTicketService;
import com.si.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that WebSocket authentication consumes a one-time ticket, binds only the user id,
 * and rejects legacy long-lived tokens in the query string.
 */
class JwtHandshakeInterceptorTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hmac";

    private JwtHandshakeInterceptor newInterceptor(WsTicketService ticketService) {
        return new JwtHandshakeInterceptor(ticketService);
    }

    @Test
    void validTicket_bindsUserIdAndIsConsumedOnce() {
        WsTicketService ticketService = new WsTicketService();
        long userId = 7L;
        String ticket = ticketService.issue(userId);
        JwtHandshakeInterceptor interceptor = newInterceptor(ticketService);
        Map<String, Object> attributes = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(
                request("ws://localhost/ws/asr?ticket=" + ticket),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);

        assertTrue(allowed);
        assertEquals(userId, attributes.get(JwtHandshakeInterceptor.ATTRIBUTE_AUTHENTICATED_USER_ID));
        assertFalse(attributes.containsValue(ticket));

        // 一次性:同一票据二次握手必须被拒绝。
        boolean reused = interceptor.beforeHandshake(
                request("ws://localhost/ws/asr?ticket=" + ticket),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>());
        assertFalse(reused);
    }

    @Test
    void legacyTokenQuery_isRejected() {
        JwtHandshakeInterceptor interceptor = newInterceptor(new WsTicketService());
        String token = JwtUtil.createToken(42L, "operator", 0, 60_000L, SECRET);
        ServerHttpRequest request = request("ws://localhost/ws/asr?token=" + token);
        Map<String, Object> attributes = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(
                request,
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                attributes
        );

        assertFalse(allowed);
        assertFalse(attributes.containsKey(JwtHandshakeInterceptor.ATTRIBUTE_AUTHENTICATED_USER_ID));
        assertFalse(attributes.containsValue(token));
    }

    @Test
    void invalidToken_isRejected() {
        JwtHandshakeInterceptor interceptor = newInterceptor(new WsTicketService());

        boolean allowed = interceptor.beforeHandshake(
                request("ws://localhost/ws/asr?token=invalid"),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                new HashMap<>()
        );

        assertFalse(allowed);
    }

    private ServerHttpRequest request(String uri) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }
}
