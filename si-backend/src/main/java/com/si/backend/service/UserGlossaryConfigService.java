package com.si.backend.service;

import com.si.backend.entity.UserGlossaryConfig;
import com.si.backend.mapper.UserGlossaryConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages user-level glossary configuration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserGlossaryConfigService {

    private final UserGlossaryConfigMapper glossaryConfigMapper;

    @PostConstruct
    public void initTable() {
        log.info("[UserGlossaryConfigService] initTable start");
        glossaryConfigMapper.createTableIfNotExists();
        log.info("[UserGlossaryConfigService] initTable end");
    }

    public List<UserGlossaryConfig> list(Long userId) {
        log.info("[UserGlossaryConfigService] list start, userId={}", userId);
        List<UserGlossaryConfig> configs = glossaryConfigMapper.findByUserId(userId);
        log.info("[UserGlossaryConfigService] list end, userId={}, count={}", userId, configs.size());
        return configs;
    }

    public String resolveGlossaryId(Long userId, String sourceLang, String targetLang) {
        UserGlossaryConfig config = glossaryConfigMapper.findEnabledDirection(userId, sourceLang, targetLang);
        return config == null ? null : config.getGlossaryId();
    }

    @Transactional
    public UserGlossaryConfig upsert(Long userId, UserGlossaryConfig config) {
        log.info("[UserGlossaryConfigService] upsert start, userId={}, sourceLang={}, targetLang={}",
                userId, config.getSourceLang(), config.getTargetLang());
        config.setUserId(userId);
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        glossaryConfigMapper.upsert(config);
        log.info("[UserGlossaryConfigService] upsert end, userId={}, sourceLang={}, targetLang={}",
                userId, config.getSourceLang(), config.getTargetLang());
        return config;
    }

    @Transactional
    public void delete(Long userId, Long id) {
        log.info("[UserGlossaryConfigService] delete start, userId={}, id={}", userId, id);
        glossaryConfigMapper.deleteById(id, userId);
        log.info("[UserGlossaryConfigService] delete end, userId={}, id={}", userId, id);
    }
}
