package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.facade.InterpretationFacade;
import com.si.backend.dto.SaveInterpretationResultRequest;
import com.si.backend.dto.MapSessionSpeakerRequest;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.dto.StopInterpretationRequest;
import com.si.backend.dto.UpdateSessionTitleRequest;
import com.si.backend.vo.InterpretationRecordVo;
import com.si.backend.vo.InterpretationResultItemVo;
import com.si.backend.vo.InterpretationSessionVo;
import com.si.backend.vo.SessionSpeakerVoiceVo;
import com.si.backend.vo.SessionSpeakerIdentityVo;
import com.si.backend.vo.VoiceUsageRecordVo;
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
    public Result<java.util.Map<String, Object>> stopInterpretation(@Valid @RequestBody StopInterpretationRequest request) {
        log.info("[InterpretationController] stopInterpretation start, sessionId={}", request.getSessionId());
        java.util.Map<String, Object> result = facade.stopInterpretation(request.getSessionId());
        log.info("[InterpretationController] stopInterpretation end, sessionId={}", request.getSessionId());
        return Result.ok(result);
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

    @GetMapping("/users/{userId}/sessions")
    public Result<java.util.List<InterpretationSessionVo>> getUserSessions(
            @PathVariable Long userId,
            @RequestParam(required = false) String keyword
    ) {
        log.info("[InterpretationController] getUserSessions start, userId={}, keyword={}", userId, keyword);
        java.util.List<InterpretationSessionVo> sessions = facade.searchUserSessions(userId, keyword);
        log.info("[InterpretationController] getUserSessions end, userId={}, count={}", userId, sessions.size());
        return Result.ok(sessions);
    }

    @PutMapping("/{sessionId}/title")
    public Result<Void> updateTitle(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @Valid @RequestBody UpdateSessionTitleRequest request
    ) {
        log.info("[InterpretationController] updateTitle start, sessionId={}, userId={}", sessionId, userId);
        facade.updateTitle(sessionId, userId, request.getTitle());
        log.info("[InterpretationController] updateTitle end, sessionId={}, userId={}", sessionId, userId);
        return Result.ok();
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId, @RequestParam Long userId) {
        log.info("[InterpretationController] deleteSession start, sessionId={}, userId={}", sessionId, userId);
        facade.deleteSession(sessionId, userId);
        log.info("[InterpretationController] deleteSession end, sessionId={}, userId={}", sessionId, userId);
        return Result.ok();
    }

    @PostMapping("/results")
    public Result<InterpretationResultItemVo> saveResult(@Valid @RequestBody SaveInterpretationResultRequest request) {
        log.info("[InterpretationController] saveResult start, sessionId={}", request.getSessionId());
        InterpretationResultItemVo result = facade.saveResult(request);
        log.info("[InterpretationController] saveResult end, sessionId={}, resultId={}",
                request.getSessionId(), result.getId());
        return Result.ok(result);
    }

    @GetMapping("/public/user/{userId}/active")
    public Result<String> getActiveSessionForUser(@PathVariable Long userId) {
        log.info("[InterpretationController] getActiveSessionForUser, userId={}", userId);
        String sessionId = facade.getActiveSessionIdForUser(userId);
        log.info("[InterpretationController] getActiveSessionForUser end, userId={}, sessionId={}", userId, sessionId);
        return Result.ok(sessionId);
    }

    @GetMapping("/public/{sessionId}/results")
    public Result<java.util.List<InterpretationResultItemVo>> getPublicResults(@PathVariable String sessionId) {
        log.info("[InterpretationController] getPublicResults start, sessionId={}", sessionId);
        java.util.List<InterpretationResultItemVo> results = facade.listPublicResults(sessionId);
        log.info("[InterpretationController] getPublicResults end, sessionId={}, count={}", sessionId, results.size());
        return Result.ok(results);
    }

    @GetMapping("/records/{sessionId}")
    public Result<java.util.List<InterpretationRecordVo>> getRecords(@PathVariable String sessionId) {
        log.info("[InterpretationController] getRecords start, sessionId={}", sessionId);
        java.util.List<InterpretationRecordVo> records = facade.getRecords(sessionId);
        log.info("[InterpretationController] getRecords end, sessionId={}, count={}", sessionId, records.size());
        return Result.ok(records);
    }

    @GetMapping("/voice-usage/{sessionId}")
    public Result<java.util.List<VoiceUsageRecordVo>> getVoiceUsage(@PathVariable String sessionId) {
        log.info("[InterpretationController] getVoiceUsage start, sessionId={}", sessionId);
        java.util.List<VoiceUsageRecordVo> records = facade.getVoiceUsage(sessionId);
        log.info("[InterpretationController] getVoiceUsage end, sessionId={}, count={}", sessionId, records.size());
        return Result.ok(records);
    }

    @GetMapping("/speaker-voices/{sessionId}")
    public Result<java.util.List<SessionSpeakerVoiceVo>> getSpeakerVoices(@PathVariable String sessionId) {
        log.info("[InterpretationController] getSpeakerVoices start, sessionId={}", sessionId);
        java.util.List<SessionSpeakerVoiceVo> speakerVoices = facade.getSpeakerVoices(sessionId);
        log.info("[InterpretationController] getSpeakerVoices end, sessionId={}, count={}", sessionId, speakerVoices.size());
        return Result.ok(speakerVoices);
    }

    @GetMapping("/session-speakers/{sessionId}")
    public Result<java.util.List<SessionSpeakerIdentityVo>> getSessionSpeakerIdentities(@PathVariable String sessionId) {
        log.info("[InterpretationController] getSessionSpeakerIdentities start, sessionId={}", sessionId);
        java.util.List<SessionSpeakerIdentityVo> identities = facade.getSessionSpeakerIdentities(sessionId);
        log.info("[InterpretationController] getSessionSpeakerIdentities end, sessionId={}, count={}", sessionId, identities.size());
        return Result.ok(identities);
    }

    @PutMapping("/session-speakers/{sessionId}/{speakerId}")
    public Result<SessionSpeakerIdentityVo> mapSessionSpeaker(
            @PathVariable String sessionId,
            @PathVariable String speakerId,
            @Valid @RequestBody MapSessionSpeakerRequest request
    ) {
        log.info("[InterpretationController] mapSessionSpeaker start, sessionId={}, speakerId={}, personName={}",
                sessionId, speakerId, request.getPersonName());
        SessionSpeakerIdentityVo identity = facade.mapSessionSpeaker(sessionId, speakerId, request.getPersonName());
        log.info("[InterpretationController] mapSessionSpeaker end, sessionId={}, speakerId={}", sessionId, speakerId);
        return Result.ok(identity);
    }
}
