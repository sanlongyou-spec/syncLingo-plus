package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.facade.MeetingSummaryFacade;
import com.si.backend.vo.MeetingSummaryVo;
import com.si.backend.util.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 会议纪要控制器，提供按 session 生成纪要接口。
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.SUMMARY_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.OWN,
        expectedStatuses = {200, 400, 401, 404})
@RequestMapping("/api/summary")
@RequiredArgsConstructor
public class MeetingSummaryController {

    private final MeetingSummaryFacade facade;

    @GetMapping("/{sessionId}")
    public Result<MeetingSummaryVo> getSummary(@PathVariable String sessionId) {
        log.info("[MeetingSummaryController] getSummary start, sessionId={}", sessionId);
        MeetingSummaryVo summary = facade.getSummary(AuthContext.requireActor(), sessionId);
        log.info("[MeetingSummaryController] getSummary end, sessionId={}", sessionId);
        return Result.ok(summary);
    }

    @PostMapping("/{sessionId}")
    public Result<MeetingSummaryVo> regenerateSummary(
            @PathVariable String sessionId,
            @RequestBody(required = false) RegenerateSummaryRequest request) {
        log.info("[MeetingSummaryController] regenerateSummary start, sessionId={}", sessionId);
        String customRequirements = request != null ? request.getCustomRequirements() : null;
        MeetingSummaryVo summary = facade.regenerateSummary(
                AuthContext.requireActor(), sessionId, customRequirements);
        log.info("[MeetingSummaryController] regenerateSummary end, sessionId={}", sessionId);
        return Result.ok(summary);
    }

    public static class RegenerateSummaryRequest {
        private String customRequirements;
        public String getCustomRequirements() { return customRequirements; }
        public void setCustomRequirements(String customRequirements) { this.customRequirements = customRequirements; }
    }
}
