package com.si.backend.config;

import com.si.backend.mapper.UserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P1:为存量 si_user 表补 status 列(ACTIVE/PENDING/DISABLED)。
 * 沿用项目既有"启动期加列、列已存在则忽略 Duplicate column"模式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSchemaInitializer {

    private final UserMapper userMapper;

    @PostConstruct
    public void init() {
        try {
            userMapper.addStatusColumnIfNotExists();
            log.info("[UserSchemaInitializer] si_user.status column added");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Duplicate column")) {
                log.info("[UserSchemaInitializer] si_user.status already exists");
            } else {
                log.warn("[UserSchemaInitializer] add status column failed: {}", msg);
            }
        }
    }
}
