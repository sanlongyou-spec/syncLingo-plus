package com.si.backend.facade;

import com.si.backend.dto.LoginRequest;
import com.si.backend.dto.LoginResponse;
import com.si.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFacade {

    private final AuthService authService;

    public LoginResponse login(LoginRequest request) {
        log.info("[AuthFacade] login start, username={}", request.getUsername());
        LoginResponse response = authService.login(request.getUsername(), request.getPassword());
        log.info("[AuthFacade] login end, username={}, userId={}", request.getUsername(), response.getUserId());
        return response;
    }
}
