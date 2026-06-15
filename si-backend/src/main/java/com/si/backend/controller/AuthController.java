package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.LoginRequest;
import com.si.backend.dto.LoginResponse;
import com.si.backend.facade.AuthFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthFacade facade;

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.AUTH_LOGIN,
            scope = com.si.backend.security.authorization.ResourceScope.NONE,
            expectedStatuses = {200, 400, 401})
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("[AuthController] login start, username={}", request.getUsername());
        LoginResponse response = facade.login(request);
        log.info("[AuthController] login end, username={}, userId={}", request.getUsername(), response.getUserId());
        return Result.ok(response);
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
}
