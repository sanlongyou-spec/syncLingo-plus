package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.SaveUserGlossaryConfigRequest;
import com.si.backend.facade.UserGlossaryConfigFacade;
import com.si.backend.vo.UserGlossaryConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User glossary configuration endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/glossaries")
@RequiredArgsConstructor
public class UserGlossaryConfigController {

    private final UserGlossaryConfigFacade facade;

    @GetMapping
    public Result<List<UserGlossaryConfigVo>> list(@RequestParam Long userId) {
        log.info("[UserGlossaryConfigController] list start, userId={}", userId);
        List<UserGlossaryConfigVo> result = facade.list(userId);
        log.info("[UserGlossaryConfigController] list end, userId={}, count={}", userId, result.size());
        return Result.ok(result);
    }

    @PostMapping
    public Result<UserGlossaryConfigVo> upsert(@RequestParam Long userId, @RequestBody SaveUserGlossaryConfigRequest request) {
        log.info("[UserGlossaryConfigController] upsert start, userId={}, sourceLang={}, targetLang={}",
                userId, request.getSourceLang(), request.getTargetLang());
        UserGlossaryConfigVo result = facade.upsert(userId, request);
        log.info("[UserGlossaryConfigController] upsert end, userId={}, id={}", userId, result.getId());
        return Result.ok(result);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, @RequestParam Long userId) {
        log.info("[UserGlossaryConfigController] delete start, userId={}, id={}", userId, id);
        facade.delete(userId, id);
        log.info("[UserGlossaryConfigController] delete end, userId={}, id={}", userId, id);
        return Result.ok();
    }
}
