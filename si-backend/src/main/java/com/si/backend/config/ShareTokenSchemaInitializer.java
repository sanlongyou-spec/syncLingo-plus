package com.si.backend.config;

import com.si.backend.mapper.ShareTokenMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P4:启动期建 share_token 表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShareTokenSchemaInitializer {

    private final ShareTokenMapper shareTokenMapper;

    @PostConstruct
    public void init() {
        try {
            shareTokenMapper.createTableIfNotExists();
            // 固定有效期策略上线：一次性失效所有"无过期时间"的历史共享链接。
            int revoked = shareTokenMapper.revokeLegacyTokensWithoutExpiry();
            log.info("[ShareTokenSchemaInitializer] share_token table ready, revokedLegacyTokens={}", revoked);
        } catch (Exception e) {
            log.warn("[ShareTokenSchemaInitializer] create share_token failed: {}", e.getMessage());
        }
    }
}
