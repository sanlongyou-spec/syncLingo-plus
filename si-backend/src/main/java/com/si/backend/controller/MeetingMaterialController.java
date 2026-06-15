package com.si.backend.controller;

import com.si.backend.common.Result;
import com.si.backend.dto.SaveMeetingMaterialRequest;
import com.si.backend.facade.MeetingMaterialFacade;
import com.si.backend.vo.MeetingMaterialVo;
import com.si.backend.util.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for meeting agenda/report material and generated summaries.
 */
@Slf4j
@RestController
@com.si.backend.security.authorization.AuthorizationSpec(
        identity = com.si.backend.security.authorization.IdentityType.USER,
        permission = com.si.backend.security.authorization.PermissionCode.MEETING_CONTENT_MANAGE,
        scope = com.si.backend.security.authorization.ResourceScope.OWN,
        expectedStatuses = {200, 400, 401, 404})
@RequestMapping("/api/meeting-materials")
@RequiredArgsConstructor
public class MeetingMaterialController {

    private final MeetingMaterialFacade facade;

    @GetMapping("/sessions/{sessionId}")
    public Result<MeetingMaterialVo> getMaterial(@PathVariable String sessionId) {
        log.info("[MeetingMaterialController] getMaterial start, sessionId={}", sessionId);
        MeetingMaterialVo material = facade.getMaterial(AuthContext.requireActor(), sessionId);
        log.info("[MeetingMaterialController] getMaterial end, sessionId={}", sessionId);
        return Result.ok(material);
    }

    @PutMapping("/sessions/{sessionId}")
    public Result<MeetingMaterialVo> saveMaterial(
            @PathVariable String sessionId,
            @RequestBody SaveMeetingMaterialRequest request
    ) {
        log.info("[MeetingMaterialController] saveMaterial start, sessionId={}", sessionId);
        MeetingMaterialVo material = facade.saveMaterial(AuthContext.requireActor(), sessionId, request);
        log.info("[MeetingMaterialController] saveMaterial end, sessionId={}", sessionId);
        return Result.ok(material);
    }

    @PostMapping("/sessions/{sessionId}/summary")
    public Result<MeetingMaterialVo> generateSummary(@PathVariable String sessionId) {
        log.info("[MeetingMaterialController] generateSummary start, sessionId={}", sessionId);
        MeetingMaterialVo material = facade.generateSummary(AuthContext.requireActor(), sessionId);
        log.info("[MeetingMaterialController] generateSummary end, sessionId={}", sessionId);
        return Result.ok(material);
    }
}
