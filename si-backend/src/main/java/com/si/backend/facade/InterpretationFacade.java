package com.si.backend.facade;

import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.TranslationService;
import com.si.backend.service.AudioOutputService;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.vo.InterpretationSessionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 同传会话门面层，协调会话管理与翻译音频输出流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterpretationFacade {

    private final InterpretationSessionService sessionService;
    private final TranslationService translationService;
    private final AudioOutputService audioOutputService;

    @Transactional
    public String startInterpretation(StartInterpretationRequest request) {
        log.info("[InterpretationFacade] startInterpretation start, userId={}, sourceLang={}, targetLang={}, voiceId={}",
                request.getUserId(), request.getSourceLang(), request.getTargetLang(), request.getVoiceId());
        String sessionId = UUID.randomUUID().toString();
        InterpretationSession session = sessionService.startSession(
                sessionId,
                request.getUserId(),
                request.getSourceLang(),
                request.getTargetLang(),
                request.getVoiceId()
        );
        log.info("[InterpretationFacade] startInterpretation end, sessionId={}, userId={}, sourceLang={}→targetLang={}",
                sessionId, request.getUserId(), request.getSourceLang(), request.getTargetLang());
        return sessionId;
    }

    public void stopInterpretation(String sessionId) {
        log.info("[InterpretationFacade] stopInterpretation start, sessionId={}", sessionId);
        sessionService.stopSession(sessionId);
        log.info("[InterpretationFacade] stopInterpretation end, sessionId={}", sessionId);
    }

    public InterpretationSessionVo getSessionStatus(String sessionId) {
        log.info("[InterpretationFacade] getSessionStatus start, sessionId={}", sessionId);
        InterpretationSessionVo vo = sessionService.getSession(sessionId)
                .map(session -> InterpretationSessionVo.builder()
                        .sessionId(session.getSessionId())
                        .sourceLang(session.getSourceLang())
                        .targetLang(session.getTargetLang())
                        .status(session.getStatus())
                        .voiceId(session.getVoiceId())
                        .startTime(session.getStartTime() != null ?
                                session.getStartTime().toString() : null)
                        .build())
                .orElse(null);
        log.info("[InterpretationFacade] getSessionStatus end, sessionId={}, found={}", sessionId, vo != null);
        return vo;
    }

    public InterpretationSessionVo getSessionHistory(String sessionId) {
        log.info("[InterpretationFacade] getSessionHistory start, sessionId={}", sessionId);
        InterpretationSession session = sessionService.getSessionHistory(sessionId);
        if (session == null) {
            log.warn("[InterpretationFacade] getSessionHistory not found, sessionId={}", sessionId);
            return null;
        }
        InterpretationSessionVo vo = InterpretationSessionVo.builder()
                .sessionId(session.getSessionId())
                .sourceLang(session.getSourceLang())
                .targetLang(session.getTargetLang())
                .status(session.getStatus())
                .voiceId(session.getVoiceId())
                .startTime(session.getStartTime() != null ?
                        session.getStartTime().toString() : null)
                .endTime(session.getEndTime() != null ?
                        session.getEndTime().toString() : null)
                .build();
        log.info("[InterpretationFacade] getSessionHistory end, sessionId={}", sessionId);
        return vo;
    }

    public String translateAndOutput(String text, String sourceLang, String targetLang, byte[] ttsPcm) {
        log.info("[InterpretationFacade] translateAndOutput start, textLen={}, sourceLang={}, targetLang={}, pcmLen={}",
                text != null ? text.length() : 0, sourceLang, targetLang, ttsPcm != null ? ttsPcm.length : 0);
        String translated = translationService.translate(text, sourceLang, targetLang);
        if (ttsPcm != null && ttsPcm.length > 0) {
            audioOutputService.outputTargetAudio(ttsPcm);
        }
        log.info("[InterpretationFacade] translateAndOutput end, textLen={}, translatedLen={}",
                text != null ? text.length() : 0, translated != null ? translated.length() : 0);
        return translated;
    }
}
