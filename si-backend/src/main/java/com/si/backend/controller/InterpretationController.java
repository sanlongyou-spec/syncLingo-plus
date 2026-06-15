package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.MapSessionSpeakerRequest;
import com.si.backend.dto.SaveInterpretationResultRequest;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.dto.StopInterpretationRequest;
import com.si.backend.dto.UpdateSessionTitleRequest;
import com.si.backend.facade.InterpretationFacade;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.security.AnonymousRequestRateLimiter;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.InterpretationRecordVo;
import com.si.backend.vo.InterpretationResultItemVo;
import com.si.backend.vo.InterpretationSessionVo;
import com.si.backend.vo.PublicSessionInfoVo;
import com.si.backend.vo.SessionSpeakerMappingVo;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interpretation session HTTP entry points.
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.INTERPRETATION_OPERATE,
        scope = com.si.backend.security.authorization.ResourceScope.OWN,
        expectedStatuses = {200, 400, 401, 403, 404})
@RequestMapping("/api/interpretation")
@RequiredArgsConstructor
public class InterpretationController {

    private final InterpretationFacade facade;
    private final AnonymousRequestRateLimiter anonymousRequestRateLimiter;

    private static final int PUBLIC_ACTIVE_LIMIT_PER_MINUTE = 60;
    private static final int PUBLIC_LATENCY_LIMIT_PER_MINUTE = 120;
    private static final int PUBLIC_LATENCY_MAX_BODY_CHARS = 2_048;
    private static final Set<String> PUBLIC_LATENCY_FIELDS = Set.of(
            "sessionId",
            "lang",
            "e2eMs",
            "captureMs",
            "rttMs",
            "tailMs",
            "outputLatencyMs",
            "backlogMs",
            "playbackRateMilli"
    );

    @PostMapping("/start")
    public Result<String> startInterpretation(@Valid @RequestBody StartInterpretationRequest request) {
        log.info("[InterpretationController] startInterpretation start, userId={}, sourceLang={}, targetLang={}",
                request.getUserId(), request.getSourceLang(), request.getTargetLang());
        String sessionId = facade.startInterpretation(AuthContext.requireActor(), request);
        log.info("[InterpretationController] startInterpretation end, sessionId={}", sessionId);
        return Result.ok(sessionId);
    }

    @PostMapping("/stop")
    public Result<Map<String, Object>> stopInterpretation(@Valid @RequestBody StopInterpretationRequest request) {
        log.info("[InterpretationController] stopInterpretation start, sessionId={}", request.getSessionId());
        Map<String, Object> result = facade.stopInterpretation(AuthContext.requireActor(), request.getSessionId());
        log.info("[InterpretationController] stopInterpretation end, sessionId={}", request.getSessionId());
        return Result.ok(result);
    }

    @GetMapping("/status/{sessionId}")
    public Result<InterpretationSessionVo> getStatus(@PathVariable String sessionId) {
        return Result.ok(facade.getSessionStatus(AuthContext.requireActor(), sessionId));
    }

    @GetMapping("/history/{sessionId}")
    public Result<InterpretationSessionVo> getHistory(@PathVariable String sessionId) {
        return Result.ok(facade.getSessionHistory(AuthContext.requireActor(), sessionId));
    }

    @GetMapping("/users/{userId}/sessions")
    public Result<List<InterpretationSessionVo>> getUserSessions(
            @PathVariable Long userId,
            @RequestParam(required = false) String keyword
    ) {
        userId = AuthContext.requireSelf(userId);
        return Result.ok(facade.searchUserSessions(userId, keyword));
    }

    @PutMapping("/{sessionId}/title")
    public Result<Void> updateTitle(
            @PathVariable String sessionId,
            @RequestParam Long userId,
            @Valid @RequestBody UpdateSessionTitleRequest request
    ) {
        AuthenticatedActor actor = AuthContext.requireActor();
        AuthContext.requireSelf(actor, userId);
        facade.updateTitle(actor, sessionId, request.getTitle());
        return Result.ok();
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId, @RequestParam Long userId) {
        AuthenticatedActor actor = AuthContext.requireActor();
        AuthContext.requireSelf(actor, userId);
        facade.deleteSession(actor, sessionId);
        return Result.ok();
    }

    @PostMapping("/results")
    public Result<InterpretationResultItemVo> saveResult(@Valid @RequestBody SaveInterpretationResultRequest request) {
        return Result.ok(facade.saveResult(AuthContext.requireActor(), request));
    }

    @GetMapping("/records/{sessionId}")
    public Result<List<InterpretationRecordVo>> getRecords(@PathVariable String sessionId) {
        return Result.ok(facade.getRecords(AuthContext.requireActor(), sessionId));
    }

    @GetMapping("/session-speakers/{sessionId}")
    public Result<List<SessionSpeakerMappingVo>> getSessionSpeakerMappings(@PathVariable String sessionId) {
        return Result.ok(facade.getSessionSpeakerMappings(AuthContext.requireActor(), sessionId));
    }

    @PutMapping("/session-speakers/{sessionId}/{speakerId}")
    public Result<SessionSpeakerMappingVo> mapSessionSpeaker(
            @PathVariable String sessionId,
            @PathVariable String speakerId,
            @Valid @RequestBody MapSessionSpeakerRequest request
    ) {
        return Result.ok(facade.mapSessionSpeaker(
                AuthContext.requireActor(), sessionId, speakerId, request.getPersonName()));
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.SHARE_READ,
            scope = com.si.backend.security.authorization.ResourceScope.PUBLIC,
            expectedStatuses = {200, 429})
    @GetMapping("/public/user/{userId}/active")
    public Result<String> getActiveSessionForUser(@PathVariable Long userId, HttpServletRequest request) {
        anonymousRequestRateLimiter.requireAllowed(
                "public-active",
                request.getRemoteAddr() + ":" + userId,
                PUBLIC_ACTIVE_LIMIT_PER_MINUTE,
                60
        );
        return Result.ok(facade.getActiveSessionIdForUser(userId));
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.SHARE_READ,
            scope = com.si.backend.security.authorization.ResourceScope.PUBLIC,
            expectedStatuses = {200, 400, 429})
    @PostMapping("/public/latency")
    public Result<Void> reportLatency(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (request.getContentLengthLong() > PUBLIC_LATENCY_MAX_BODY_CHARS) {
            throw com.si.backend.common.BizException.of(
                    com.si.backend.common.ErrorCode.BAD_REQUEST,
                    "Invalid latency report"
            );
        }
        validatePublicLatencyBody(body);
        anonymousRequestRateLimiter.requireAllowed(
                "public-latency",
                request.getRemoteAddr() + ":" + body.getOrDefault("sessionId", "unknown"),
                PUBLIC_LATENCY_LIMIT_PER_MINUTE,
                60
        );
        log.info("[InterpretationController] e2e client latency, sessionId={}, lang={}, e2eMs={}, captureMs={}, rttMs={}, tailMs={}, outputLatencyMs={}, backlogMs={}, playbackRateMilli={}",
                body.get("sessionId"), body.get("lang"), body.get("e2eMs"), body.get("captureMs"), body.get("rttMs"),
                body.get("tailMs"), body.get("outputLatencyMs"), body.get("backlogMs"), body.get("playbackRateMilli"));
        return Result.ok();
    }

    private void validatePublicLatencyBody(Map<String, Object> body) {
        if (body == null
                || !PUBLIC_LATENCY_FIELDS.containsAll(body.keySet())
                || body.toString().length() > PUBLIC_LATENCY_MAX_BODY_CHARS) {
            throw com.si.backend.common.BizException.of(
                    com.si.backend.common.ErrorCode.BAD_REQUEST,
                    "Invalid latency report"
            );
        }
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.SHARE_READ,
            scope = com.si.backend.security.authorization.ResourceScope.PUBLIC,
            expectedStatuses = {200, 404})
    @GetMapping("/public/{sessionId}/info")
    public Result<PublicSessionInfoVo> getPublicSessionInfo(@PathVariable String sessionId) {
        return Result.ok(facade.getPublicSessionInfo(sessionId));
    }

    @com.si.backend.security.authorization.AuthorizationSpec(
            identity = com.si.backend.security.authorization.IdentityType.ANONYMOUS,
            permission = com.si.backend.security.authorization.PermissionCode.SHARE_READ,
            scope = com.si.backend.security.authorization.ResourceScope.PUBLIC,
            expectedStatuses = {200, 404})
    @GetMapping("/public/{sessionId}/results")
    public Result<List<InterpretationResultItemVo>> getPublicResults(@PathVariable String sessionId) {
        return Result.ok(facade.listPublicResults(sessionId));
    }
}
