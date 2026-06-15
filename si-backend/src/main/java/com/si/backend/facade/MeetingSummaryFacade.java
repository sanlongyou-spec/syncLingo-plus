package com.si.backend.facade;

import com.si.backend.service.MeetingSummaryService;
import com.si.backend.service.ResourceOwnershipPolicy;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.vo.MeetingSummaryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingSummaryFacade {

    private final MeetingSummaryService meetingSummaryService;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;

    public MeetingSummaryVo getSummary(AuthenticatedActor actor, String sessionId) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[MeetingSummaryFacade] getSummary start, sessionId={}", sessionId);
        MeetingSummaryVo summary = meetingSummaryService.getSummary(sessionId);
        log.info("[MeetingSummaryFacade] getSummary end, sessionId={}", sessionId);
        return summary;
    }

    public MeetingSummaryVo regenerateSummary(
            AuthenticatedActor actor,
            String sessionId,
            String customRequirements
    ) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[MeetingSummaryFacade] regenerateSummary start, sessionId={}", sessionId);
        MeetingSummaryVo summary = meetingSummaryService.regenerateSummary(sessionId, customRequirements);
        log.info("[MeetingSummaryFacade] regenerateSummary end, sessionId={}", sessionId);
        return summary;
    }
}
