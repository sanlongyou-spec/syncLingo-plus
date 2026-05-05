package com.si.backend.facade;

import com.si.backend.service.InterpretationSessionService;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.vo.InterpretationSessionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 同传会话门面层，协调会话管理与翻译流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterpretationFacade {

    private final InterpretationSessionService sessionService;

    @Transactional
    public String startInterpretation(StartInterpretationRequest request) {
        log.info("[InterpretationFacade] startInterpretation, userId={}, sourceLang={}, targetLang={}, voiceId={}",
                request.getUserId(), request.getSourceLang(), request.getTargetLang(), request.getVoiceId());
        String sessionId = UUID.randomUUID().toString();
        sessionService.startSession(
                sessionId,
                request.getUserId(),
                request.getSourceLang(),
                request.getTargetLang(),
                request.getVoiceId()
        );
        log.info("[InterpretationFacade] startInterpretation done, sessionId={}", sessionId);
        return sessionId;
    }

    public void stopInterpretation(String sessionId) {
        log.info("[InterpretationFacade] stopInterpretation, sessionId={}", sessionId);
        sessionService.stopSession(sessionId);
    }

    public InterpretationSessionVo getSessionStatus(String sessionId) {
        return sessionService.getSession(sessionId)
                .map(session -> InterpretationSessionVo.builder()
                        .sessionId(session.getSessionId())
                        .sourceLang(session.getSourceLang())
                        .targetLang(session.getTargetLang())
                        .status(session.getStatus())
                        .voiceId(session.getVoiceId())
                        .startTime(session.getStartTime() != null ? session.getStartTime().toString() : null)
                        .build())
                .orElse(null);
    }

    public InterpretationSessionVo getSessionHistory(String sessionId) {
        InterpretationSession session = sessionService.getSessionHistory(sessionId);
        if (session == null) return null;
        return InterpretationSessionVo.builder()
                .sessionId(session.getSessionId())
                .sourceLang(session.getSourceLang())
                .targetLang(session.getTargetLang())
                .status(session.getStatus())
                .voiceId(session.getVoiceId())
                .startTime(session.getStartTime() != null ? session.getStartTime().toString() : null)
                .endTime(session.getEndTime() != null ? session.getEndTime().toString() : null)
                .build();
    }
}
