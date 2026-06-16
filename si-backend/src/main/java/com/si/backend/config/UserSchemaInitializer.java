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
        addColumn("status", userMapper::addStatusColumnIfNotExists);
        addColumn("token_version", userMapper::addTokenVersionColumnIfNotExists);
    }

    private void addColumn(String column, Runnable ddl) {
        try {
            ddl.run();
            log.info("[UserSchemaInitializer] si_user.{} column added", column);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Duplicate column")) {
                log.info("[UserSchemaInitializer] si_user.{} already exists", column);
            } else {
                log.warn("[UserSchemaInitializer] add {} column failed: {}", column, msg);
            }
        }
    }
}
