package com.si.backend.filter;

import com.si.backend.config.JwtProperties;
import com.si.backend.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates JWT on all protected /api/** endpoints.
 * Public paths (login, share view, health) are explicitly excluded.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProperties jwtProperties;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    // Paths that do NOT require a valid JWT token
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/**",
            "/api/health",
            "/api/interpretation/public/**",
            "/actuator/**",
            "/ws/**",
            "/api/admin/**",    // protected by its own api-secret
            "/bot-api/**"       // protected by its own api-secret
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            log.warn("[JwtAuthFilter] missing token, path={}", path);
            sendUnauthorized(response, "Missing token");
            return;
        }

        String token = header.substring(7);
        Long userId = JwtUtil.verifyAndParseUserId(token, secret());
        if (userId == null) {
            log.warn("[JwtAuthFilter] invalid or expired token, path={}", path);
            sendUnauthorized(response, "Invalid or expired token");
            return;
        }

        request.setAttribute("authenticatedUserId", userId);
        chain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    private String secret() {
        String s = jwtProperties.getSecret();
        return s == null ? "" : s;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }
}
