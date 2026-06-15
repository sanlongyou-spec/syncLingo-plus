package com.si.backend.ws;

import com.si.backend.common.Constants;
import com.si.backend.config.JwtProperties;
import com.si.backend.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Validates the ASR WebSocket token and binds the authenticated user id to the connection.
 */
@Slf4j
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTRIBUTE_AUTHENTICATED_USER_ID = "authenticatedUserId";

    private final JwtProperties jwtProperties;

    public JwtHandshakeInterceptor(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String path = request.getURI().getPath();
        log.info("[JwtHandshakeInterceptor] beforeHandshake start, path={}", path);
        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst(Constants.WS_QUERY_PARAM_TOKEN);
        Long authenticatedUserId = JwtUtil.verifyAndParseUserId(token, secret());
        if (authenticatedUserId == null) {
            log.warn("[JwtHandshakeInterceptor] beforeHandshake rejected, path={}", path);
            return false;
        }
        attributes.put(ATTRIBUTE_AUTHENTICATED_USER_ID, authenticatedUserId);
        log.info("[JwtHandshakeInterceptor] beforeHandshake end, path={}, userId={}", path, authenticatedUserId);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        log.info("[JwtHandshakeInterceptor] afterHandshake, path={}, success={}",
                request.getURI().getPath(), exception == null);
    }

    private String secret() {
        return jwtProperties.getSecret() == null ? "" : jwtProperties.getSecret();
    }
}
