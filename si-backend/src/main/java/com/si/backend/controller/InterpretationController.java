package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.facade.InterpretationFacade;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.dto.StopInterpretationRequest;
import com.si.backend.vo.InterpretationSessionVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 同传控制器，提供同传会话的启动、停止、状态查询接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/interpretation")
@RequiredArgsConstructor
public class InterpretationController {

    private final InterpretationFacade facade;

    @PostMapping("/start")
    public Result<String> startInterpretation(@Valid @RequestBody StartInterpretationRequest request) {
        log.info("[InterpretationController] startInterpretation start, userId={}, sourceLang={}, targetLang={}",
                request.getUserId(), request.getSourceLang(), request.getTargetLang());
        String sessionId = facade.startInterpretation(request);
        log.info("[InterpretationController] startInterpretation end, sessionId={}", sessionId);
        return Result.ok(sessionId);
    }

    @PostMapping("/stop")
    public Result<Void> stopInterpretation(@Valid @RequestBody StopInterpretationRequest request) {
        log.info("[InterpretationController] stopInterpretation start, sessionId={}", request.getSessionId());
        facade.stopInterpretation(request.getSessionId());
        log.info("[InterpretationController] stopInterpretation end, sessionId={}", request.getSessionId());
        return Result.ok();
    }

    @GetMapping("/status/{sessionId}")
    public Result<InterpretationSessionVo> getStatus(@PathVariable String sessionId) {
        log.info("[InterpretationController] getStatus start, sessionId={}", sessionId);
        InterpretationSessionVo status = facade.getSessionStatus(sessionId);
        log.info("[InterpretationController] getStatus end, sessionId={}, found={}", sessionId, status != null);
        return Result.ok(status);
    }

    @GetMapping("/history/{sessionId}")
    public Result<InterpretationSessionVo> getHistory(@PathVariable String sessionId) {
        log.info("[InterpretationController] getHistory start, sessionId={}", sessionId);
        InterpretationSessionVo history = facade.getSessionHistory(sessionId);
        log.info("[InterpretationController] getHistory end, sessionId={}, found={}", sessionId, history != null);
        return Result.ok(history);
    }
}
