package com.si.backend.ws;

import com.si.backend.common.Constants;
import com.si.backend.service.WsTicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Validates the ASR WebSocket credential and binds the authenticated user id to the connection.
 *
 * <p>P5 Sunset:仅接受一次性 {@code ticket}(短时、单次、不含身份信息,泄露价值低),不再接受
 * 旧版把长效 JWT 放在 {@code token} query 的方式。
 */
@Slf4j
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTRIBUTE_AUTHENTICATED_USER_ID = "authenticatedUserId";

    private final WsTicketService wsTicketService;

    public JwtHandshakeInterceptor(WsTicketService wsTicketService) {
        this.wsTicketService = wsTicketService;
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
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams();

        Long authenticatedUserId = wsTicketService.consume(query.getFirst(Constants.WS_QUERY_PARAM_TICKET));
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
}
