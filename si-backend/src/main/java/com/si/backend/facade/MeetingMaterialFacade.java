package com.si.backend.facade;

import com.si.backend.dto.SaveMeetingMaterialRequest;
import com.si.backend.service.MeetingMaterialService;
import com.si.backend.service.ResourceOwnershipPolicy;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.vo.MeetingMaterialVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Facade for session-bound meeting material summaries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingMaterialFacade {

    private final MeetingMaterialService materialService;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;

    public MeetingMaterialVo getMaterial(AuthenticatedActor actor, String sessionId) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[MeetingMaterialFacade] getMaterial start, sessionId={}", sessionId);
        MeetingMaterialVo material = materialService.getMaterial(sessionId);
        log.info("[MeetingMaterialFacade] getMaterial end, sessionId={}", sessionId);
        return material;
    }

    public MeetingMaterialVo saveMaterial(
            AuthenticatedActor actor,
            String sessionId,
            SaveMeetingMaterialRequest request
    ) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[MeetingMaterialFacade] saveMaterial start, sessionId={}", sessionId);
        MeetingMaterialVo material = materialService.saveMaterial(sessionId, request);
        log.info("[MeetingMaterialFacade] saveMaterial end, sessionId={}", sessionId);
        return material;
    }

    public MeetingMaterialVo generateSummary(AuthenticatedActor actor, String sessionId) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[MeetingMaterialFacade] generateSummary start, sessionId={}", sessionId);
        MeetingMaterialVo summary = materialService.generateSummary(sessionId);
        log.info("[MeetingMaterialFacade] generateSummary end, sessionId={}", sessionId);
        return summary;
    }
}
