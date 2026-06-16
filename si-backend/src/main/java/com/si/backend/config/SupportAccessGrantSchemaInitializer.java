package com.si.backend.config;

import com.si.backend.mapper.SupportAccessGrantMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P3:启动期建 support_access_grant 表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportAccessGrantSchemaInitializer {

    private final SupportAccessGrantMapper supportAccessGrantMapper;

    @PostConstruct
    public void init() {
        try {
            supportAccessGrantMapper.createTableIfNotExists();
            log.info("[SupportAccessGrantSchemaInitializer] support_access_grant table ready");
        } catch (Exception e) {
            log.warn("[SupportAccessGrantSchemaInitializer] create support_access_grant failed: {}", e.getMessage());
        }
    }
}
