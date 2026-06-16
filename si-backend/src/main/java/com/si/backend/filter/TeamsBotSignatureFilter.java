package com.si.backend.filter;

import com.si.backend.common.BizException;
import com.si.backend.config.ServiceSignatureProperties;
import com.si.backend.security.ServiceSignature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * P4 上行(C# Bot→Java)服务签名校验,仅作用于 {@code /api/teams-bot/**}。运行在 JwtAuthFilter 之前。
 *
 * <p>执行策略:
 * <ul>
 *   <li>未配置 upstream-key:不校验,放行(由 service 层静态 api-secret 兜底)。</li>
 *   <li>已配置 upstream-key 且带签名头:严格验签,失败 401。</li>
 *   <li>已配置 upstream-key 但缺签名头:默认拒绝;仅 upstream-required=false 时迁移放行。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class TeamsBotSignatureFilter extends OncePerRequestFilter {

    private static final String TEAMS_BOT_PREFIX = "/api/teams-bot/";

    private final ServiceSignatureProperties signatureProperties;
    private final ServiceSignature serviceSignature;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(TEAMS_BOT_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        String upstreamKey = signatureProperties.getUpstreamKey();
        if (upstreamKey != null && !upstreamKey.isBlank()) {
            Map<String, String> headers = signatureHeaders(cached);
            if (serviceSignature.hasSignatureHeaders(headers)) {
                try {
                    serviceSignature.verify(
                            upstreamKey,
                            headers,
                            cached.getMethod(),
                            cached.getRequestURI(),
                            cached.getQueryString() == null ? "" : cached.getQueryString(),
                            cached.getBody());
                } catch (BizException ex) {
                    log.warn("[TeamsBotSignatureFilter] upstream signature rejected, path={}, reason={}",
                            cached.getRequestURI(), ex.getMessage());
                    writeUnauthorized(response, ex.getMessage());
                    return;
                }
            } else {
                if (signatureProperties.isUpstreamRequired()) {
                    log.warn("[TeamsBotSignatureFilter] missing upstream signature rejected, path={}",
                            cached.getRequestURI());
                    writeUnauthorized(response, "Missing service signature");
                    return;
                }
                log.warn("[TeamsBotSignatureFilter] missing upstream signature (explicit migration mode), path={}",
                        cached.getRequestURI());
            }
        }

        chain.doFilter(cached, response);
    }

    /** 只取签名相关头,避免把无关头传入校验逻辑。 */
    private Map<String, String> signatureHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        putIfPresent(headers, request, ServiceSignature.HEADER_KEY_ID);
        putIfPresent(headers, request, ServiceSignature.HEADER_TIMESTAMP);
        putIfPresent(headers, request, ServiceSignature.HEADER_NONCE);
        putIfPresent(headers, request, ServiceSignature.HEADER_SIGNATURE);
        return headers;
    }

    private void putIfPresent(Map<String, String> target, HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value != null) {
            target.put(name, value);
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        String safe = message == null ? "unauthorized" : message.replace("\"", "'");
        response.getWriter().write("{\"code\":401,\"message\":\"" + safe + "\"}");
    }
}
