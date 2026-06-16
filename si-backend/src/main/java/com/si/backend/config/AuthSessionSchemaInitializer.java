package com.si.backend.config;

import com.si.backend.mapper.AuthSessionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P5: creates auth_session for refresh-token rotation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthSessionSchemaInitializer {

    private final AuthSessionMapper authSessionMapper;

    @PostConstruct
    public void init() {
        try {
            authSessionMapper.createTableIfNotExists();
            log.info("[AuthSessionSchemaInitializer] auth_session table ready");
        } catch (Exception e) {
            log.warn("[AuthSessionSchemaInitializer] create auth_session failed: {}", e.getMessage());
        }
    }
}
