package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.integration.BotProxyIntegration;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.util.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Authenticated, allowlisted reverse proxy for the C# Teams Calling Bot.
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.BOT_OPERATE,
        scope = com.si.backend.security.authorization.ResourceScope.SELF,
        expectedStatuses = {200, 400, 401, 403, 502})
@RequestMapping("/bot-api")
@RequiredArgsConstructor
public class BotApiProxyController {

    private static final String PROXY_PREFIX = "/bot-api";
    private static final Map<String, Set<HttpMethod>> ALLOWED_OPERATIONS = Map.of(
            "/api/meetings/summary", Set.of(HttpMethod.POST)
    );

    private final BotProxyIntegration integration;

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        AuthenticatedActor actor = AuthContext.requireActor();
        String path = request.getRequestURI().substring(PROXY_PREFIX.length());
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        requireAllowedOperation(path, method);

        HttpHeaders forwardedHeaders = new HttpHeaders();
        copyHeader(request, forwardedHeaders, HttpHeaders.CONTENT_TYPE);
        copyHeader(request, forwardedHeaders, HttpHeaders.ACCEPT);

        log.info("[BotApiProxyController] proxy start, userId={}, method={}, path={}",
                actor.userId(), method, path);
        ResponseEntity<byte[]> response = integration.forward(path, method, forwardedHeaders, body);
        log.info("[BotApiProxyController] proxy end, userId={}, method={}, path={}, status={}",
                actor.userId(), method, path, response.getStatusCode().value());
        return response;
    }

    private void requireAllowedOperation(String path, HttpMethod method) {
        Set<HttpMethod> allowedMethods = ALLOWED_OPERATIONS.get(path);
        if (allowedMethods == null || !allowedMethods.contains(method)) {
            log.warn("[BotApiProxyController] operation denied, method={}, path={}", method, path);
            throw BizException.of(ErrorCode.FORBIDDEN, "Bot operation is not allowed");
        }
    }

    private void copyHeader(HttpServletRequest request, HttpHeaders target, String headerName) {
        String value = request.getHeader(headerName);
        if (value != null && !value.isBlank()) {
            target.set(headerName, value);
        }
    }
}
