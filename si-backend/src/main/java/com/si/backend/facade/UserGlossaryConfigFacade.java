package com.si.backend.facade;

import com.si.backend.dto.SaveUserGlossaryConfigRequest;
import com.si.backend.entity.UserGlossaryConfig;
import com.si.backend.service.UserGlossaryConfigService;
import com.si.backend.vo.UserGlossaryConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserGlossaryConfigFacade {

    private final UserGlossaryConfigService glossaryConfigService;

    public List<UserGlossaryConfigVo> list(Long userId) {
        log.info("[UserGlossaryConfigFacade] list start, userId={}", userId);
        List<UserGlossaryConfigVo> result = glossaryConfigService.list(userId).stream()
                .map(this::toVo)
                .toList();
        log.info("[UserGlossaryConfigFacade] list end, userId={}, count={}", userId, result.size());
        return result;
    }

    public UserGlossaryConfigVo upsert(Long userId, SaveUserGlossaryConfigRequest request) {
        log.info("[UserGlossaryConfigFacade] upsert start, userId={}, sourceLang={}, targetLang={}",
                userId, request.getSourceLang(), request.getTargetLang());
        UserGlossaryConfig result = glossaryConfigService.upsert(userId, toEntity(request));
        log.info("[UserGlossaryConfigFacade] upsert end, userId={}, id={}", userId, result.getId());
        return toVo(result);
    }

    public void delete(Long userId, Long id) {
        log.info("[UserGlossaryConfigFacade] delete start, userId={}, id={}", userId, id);
        glossaryConfigService.delete(userId, id);
        log.info("[UserGlossaryConfigFacade] delete end, userId={}, id={}", userId, id);
    }

    private UserGlossaryConfig toEntity(SaveUserGlossaryConfigRequest request) {
        UserGlossaryConfig config = new UserGlossaryConfig();
        config.setGlossaryId(request.getGlossaryId());
        config.setSourceLang(request.getSourceLang());
        config.setTargetLang(request.getTargetLang());
        config.setEnabled(request.getEnabled());
        return config;
    }

    private UserGlossaryConfigVo toVo(UserGlossaryConfig config) {
        return UserGlossaryConfigVo.builder()
                .id(config.getId())
                .userId(config.getUserId())
                .glossaryId(config.getGlossaryId())
                .sourceLang(config.getSourceLang())
                .targetLang(config.getTargetLang())
                .enabled(config.getEnabled())
                .createTime(config.getCreateTime())
                .updateTime(config.getUpdateTime())
                .build();
    }
}
