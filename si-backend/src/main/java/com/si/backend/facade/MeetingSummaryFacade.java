package com.si.backend.facade;

import com.si.backend.service.MeetingSummaryService;
import com.si.backend.vo.MeetingSummaryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingSummaryFacade {

    private final MeetingSummaryService meetingSummaryService;

    public MeetingSummaryVo getSummary(String sessionId) {
        log.info("[MeetingSummaryFacade] getSummary start, sessionId={}", sessionId);
        MeetingSummaryVo summary = meetingSummaryService.getSummary(sessionId);
        log.info("[MeetingSummaryFacade] getSummary end, sessionId={}", sessionId);
        return summary;
    }

    public MeetingSummaryVo regenerateSummary(String sessionId) {
        log.info("[MeetingSummaryFacade] regenerateSummary start, sessionId={}", sessionId);
        MeetingSummaryVo summary = meetingSummaryService.regenerateSummary(sessionId);
        log.info("[MeetingSummaryFacade] regenerateSummary end, sessionId={}", sessionId);
        return summary;
    }
}
