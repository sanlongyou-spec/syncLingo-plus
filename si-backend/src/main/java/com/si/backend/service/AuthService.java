package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.JwtProperties;
import com.si.backend.dto.LoginResponse;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.si.backend.util.JwtUtil;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtProperties jwtProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginResponse login(String username, String password) {
        log.info("[AuthService] login start, username={}", username);
        SiUser user = userMapper.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            log.warn("[AuthService] login failed, username={}", username);
            throw BizException.of(ErrorCode.AUTH_FAILED);
        }
        LoginResponse response = new LoginResponse(user.getId(), createToken(user));
        log.info("[AuthService] login end, username={}, userId={}", username, user.getId());
        return response;
    }

    private String createToken(SiUser user) {
        String secret = jwtProperties.getSecret() == null ? "" : jwtProperties.getSecret();
        return JwtUtil.createToken(user.getId(), user.getUsername(), jwtProperties.getExpirationMs(), secret);
    }
}
