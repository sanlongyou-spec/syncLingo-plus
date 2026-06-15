package com.si.backend.ws;

import com.si.backend.config.JwtProperties;
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
 * Verifies that WebSocket authentication binds only the user id and never the raw token.
 */
class JwtHandshakeInterceptorTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hmac";

    @Test
    void validToken_bindsUserIdWithoutRetainingToken() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        JwtHandshakeInterceptor interceptor = new JwtHandshakeInterceptor(properties);
        String token = JwtUtil.createToken(42L, "operator", 60_000L, SECRET);
        ServerHttpRequest request = request("ws://localhost/ws/asr?token=" + token);
        Map<String, Object> attributes = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(
                request,
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                attributes
        );

        assertTrue(allowed);
        assertEquals(42L, attributes.get(JwtHandshakeInterceptor.ATTRIBUTE_AUTHENTICATED_USER_ID));
        assertFalse(attributes.containsValue(token));
    }

    @Test
    void invalidToken_isRejected() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        JwtHandshakeInterceptor interceptor = new JwtHandshakeInterceptor(properties);

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
