package com.si.backend.filter;

import com.si.backend.config.InternalOpsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Dedicated credential guard for /api/internal/ops/**.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalOpsAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_SECRET = "X-Internal-Ops-Secret";
    private static final String PREFIX = "/api/internal/ops/";

    private final InternalOpsProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (properties.getApiSecret() == null || properties.getApiSecret().isBlank()) {
            log.error("[InternalOpsAuthFilter] app.internal-ops.api-secret is not configured");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Internal ops secret is not configured");
            return;
        }
        if (!isIpAllowed(request.getRemoteAddr())) {
            log.warn("[InternalOpsAuthFilter] remote ip denied, ip={}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Internal ops IP is not allowed");
            return;
        }
        if (!constantTimeEquals(properties.getApiSecret(), request.getHeader(HEADER_SECRET))) {
            log.warn("[InternalOpsAuthFilter] invalid credential, ip={}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Internal ops request is not authorized");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isIpAllowed(String remoteAddr) {
        if (properties.getAllowedIps() == null || properties.getAllowedIps().isEmpty()) {
            return true;
        }
        return properties.getAllowedIps().stream()
                .filter(ip -> ip != null && !ip.isBlank())
                .findAny()
                .isEmpty()
                || properties.getAllowedIps().stream()
                .anyMatch(ip -> ip != null && ip.trim().equals(remoteAddr));
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
