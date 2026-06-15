package com.si.backend.filter;

import com.si.backend.config.JwtProperties;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
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
    private final UserMapper userMapper;

    private static final String STATUS_DISABLED = "DISABLED";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    // Paths that do NOT require a valid JWT token
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/**",
            "/api/health",
            "/api/interpretation/public/**",
            "/actuator/health",
            "/ws/**",
            "/api/admin/**",    // protected by its own api-secret
            // Swagger / OpenAPI：dev 放行便于联调；生产由 springdoc.*.enabled=false 关闭(返回 404)
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**"
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

        // P1:按 userId 加载角色/状态。停用即时拒绝;DB 异常不锁人(降级为角色未知,交 REPORT_ONLY 观察)。
        try {
            SiUser user = userMapper.findById(userId);
            if (user == null) {
                log.warn("[JwtAuthFilter] token references unknown user, userId={}, path={}", userId, path);
                sendUnauthorized(response, "Invalid token");
                return;
            }
            if (STATUS_DISABLED.equalsIgnoreCase(user.getStatus())) {
                log.warn("[JwtAuthFilter] disabled account rejected, userId={}, path={}", userId, path);
                sendForbidden(response, "Account disabled");
                return;
            }
            request.setAttribute("authenticatedRole", user.getRole());
        } catch (Exception e) {
            log.warn("[JwtAuthFilter] role/status load failed (degraded, role unknown), userId={}: {}", userId, e.getMessage());
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

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
    }
}
