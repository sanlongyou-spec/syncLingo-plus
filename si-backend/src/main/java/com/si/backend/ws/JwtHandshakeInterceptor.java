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

import java.util.Map;

/**
 * WebSocket 握手拦截器，负责 JWT Token 验证与属性注入。
 */
@Slf4j
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

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
        log.info("[JwtHandshakeInterceptor] beforeHandshake, uri={}", request.getURI());
        String query = request.getURI().getQuery();

        if (query != null && query.contains(Constants.WS_QUERY_PARAM_TOKEN + "=")) {
            String token = parseToken(query);
            String secret = jwtProperties.getSecret() == null ? "" : jwtProperties.getSecret();
            if (token != null && JwtUtil.verifyAndParseUserId(token, secret) != null) {
                attributes.put("token", token);
                log.info("[JwtHandshakeInterceptor] beforeHandshake, token validated, uri={}", request.getURI());
                return true;
            }
        }

        log.warn("[JwtHandshakeInterceptor] beforeHandshake, rejected — missing or invalid token, uri={}", request.getURI());
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        log.info("[JwtHandshakeInterceptor] afterHandshake, uri={}, exception={}",
                request.getURI(), exception != null ? exception.getMessage() : "none");
    }

    private String parseToken(String query) {
        for (String param : query.split("&")) {
            if (param.startsWith(Constants.WS_QUERY_PARAM_TOKEN + "=")) {
                return param.substring(Constants.WS_QUERY_PARAM_TOKEN.length() + 1);
            }
        }
        return null;
    }

}
