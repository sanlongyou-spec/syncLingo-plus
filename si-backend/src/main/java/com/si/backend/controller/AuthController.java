package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.CaptchaChallengeResponse;
import com.si.backend.dto.LoginRequest;
import com.si.backend.dto.LoginResponse;
import com.si.backend.facade.AuthFacade;
import com.si.backend.security.AnonymousRequestRateLimiter;
import com.si.backend.service.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public static final String REFRESH_COOKIE = "si_refresh_token";
    private static final int REFRESH_LIMIT_PER_MINUTE = 120;

    private final AuthFacade facade;
    private final AnonymousRequestRateLimiter anonymousRequestRateLimiter;

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.AUTH_LOGIN,
            scope = com.si.backend.security.authorization.ResourceScope.NONE,
            expectedStatuses = {200, 400, 401, 428, 429})
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        log.info("[AuthController] login start, username={}", request.getUsername());
        // 服务端观测的 remoteAddr(代理后需配 server.forward-headers-strategy 反映真实客户端,防伪造 XFF)。
        AuthSessionService.IssuedAuth issued = facade.login(request, httpRequest.getRemoteAddr());
        writeRefreshCookie(httpResponse, issued.refreshToken(), httpRequest.isSecure());
        log.info("[AuthController] login end, username={}, userId={}", request.getUsername(), issued.response().getUserId());
        return Result.ok(issued.response());
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.AUTH_LOGIN,
            scope = com.si.backend.security.authorization.ResourceScope.NONE,
            expectedStatuses = {200, 401, 403, 429})
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
                                         HttpServletRequest httpRequest,
                                         HttpServletResponse httpResponse) {
        requireSameOrigin(httpRequest);
        anonymousRequestRateLimiter.requireAllowed(
                "auth-refresh",
                httpRequest.getRemoteAddr(),
                REFRESH_LIMIT_PER_MINUTE,
                60
        );
        AuthSessionService.IssuedAuth issued = facade.refresh(refreshToken);
        writeRefreshCookie(httpResponse, issued.refreshToken(), httpRequest.isSecure());
        return Result.ok(issued.response());
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.AUTH_LOGIN,
            scope = com.si.backend.security.authorization.ResourceScope.NONE,
            expectedStatuses = {200})
    @PostMapping("/logout")
    public Result<Void> logout(@CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
                               HttpServletResponse httpResponse) {
        facade.logout(refreshToken);
        clearRefreshCookie(httpResponse);
        return Result.ok();
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.AUTH_LOGIN,
            scope = com.si.backend.security.authorization.ResourceScope.NONE,
            expectedStatuses = {200})
    @GetMapping("/captcha")
    public Result<CaptchaChallengeResponse> issueCaptcha(@RequestParam("username") String username,
                                                         HttpServletRequest httpRequest) {
        return Result.ok(facade.issueCaptcha(username, httpRequest.getRemoteAddr()));
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.REGISTRATION_CLOSED,
            scope = com.si.backend.security.authorization.ResourceScope.NONE,
            expectedStatuses = {403})
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody LoginRequest request) {
        log.warn("[AuthController] public registration rejected, username={}", request.getUsername());
        throw BizException.of(ErrorCode.FORBIDDEN, "Public registration is closed");
    }

    private void writeRefreshCookie(HttpServletResponse response, String refreshToken, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(AuthSessionService.REFRESH_COOKIE_MAX_AGE_SECONDS)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void requireSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return;
        }
        String expected = request.getScheme() + "://" + request.getServerName();
        int port = request.getServerPort();
        if (port > 0 && port != 80 && port != 443) {
            expected += ":" + port;
        }
        if (!origin.equalsIgnoreCase(expected)) {
            log.warn("[AuthController] refresh origin rejected, origin={}, expected={}", origin, expected);
            throw BizException.of(ErrorCode.FORBIDDEN, "Invalid refresh origin");
        }
    }
}
