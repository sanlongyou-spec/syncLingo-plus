package com.si.backend.facade;

import com.si.backend.dto.SaveInterpretationResultRequest;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.InterpretationRecord;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationResultService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.MeetingSummaryService;
import com.si.backend.service.SessionSpeakerNameService;
import com.si.backend.service.UserLanguagePreferenceService;
import com.si.backend.vo.InterpretationRecordVo;
import com.si.backend.vo.InterpretationResultItemVo;
import com.si.backend.vo.InterpretationSessionVo;
import com.si.backend.vo.SessionSpeakerMappingVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 同传会话门面层，协调会话管理与翻译流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterpretationFacade {

    private final InterpretationSessionService sessionService;
    private final UserLanguagePreferenceService userLanguagePreferenceService;
    private final InterpretationResultService resultService;
    private final InterpretationRecordService recordService;
    private final SessionSpeakerNameService sessionSpeakerNameService;
    private final MeetingSummaryService meetingSummaryService;

    @Transactional
    public String startInterpretation(StartInterpretationRequest request) {
        log.info("[InterpretationFacade] startInterpretation, userId={}, sourceLang={}, targetLang={}, voiceId={}",
                request.getUserId(), request.getSourceLang(), request.getTargetLang(), request.getVoiceId());
        String sessionId = UUID.randomUUID().toString();
        List<String> enabledLanguages = userLanguagePreferenceService.resolveEnabledLanguages(
                request.getUserId(),
                request.getEnabledLanguages()
        );
        sessionService.startSession(
                sessionId,
                request.getUserId(),
                request.getSourceLang(),
                request.getTargetLang(),
                request.getTitle(),
                request.getVoiceId(),
                request.getHotwordIds(),
                enabledLanguages,
                request.getMeetingId()
        );
        log.info("[InterpretationFacade] startInterpretation done, sessionId={}", sessionId);
        return sessionId;
    }

    public Map<String, Object> stopInterpretation(String sessionId) {
        log.info("[InterpretationFacade] stopInterpretation, sessionId={}", sessionId);
        Map<String, Object> result = sessionService.stopSession(sessionId);
        CompletableFuture.runAsync(() -> meetingSummaryService.generateAndSaveAsync(sessionId));
        return result;
    }

    public InterpretationSessionVo getSessionStatus(String sessionId) {
        return sessionService.getSession(sessionId)
                .map(this::toVo)
                .orElse(null);
    }

    public String getActiveSessionIdForUser(Long userId) {
        return sessionService.getActiveSessionForUser(userId)
                .map(InterpretationSession::getSessionId)
                .orElse(null);
    }

    public com.si.backend.vo.PublicSessionInfoVo getPublicSessionInfo(String sessionId) {
        InterpretationSession session = sessionService.getSession(sessionId)
                .orElseGet(() -> sessionService.getSessionHistory(sessionId));
        if (session == null) return null;
        List<String> enabledLanguages = parseEnabledLanguages(session.getEnabledLanguages());
        return com.si.backend.vo.PublicSessionInfoVo.builder()
                .sessionId(sessionId)
                .title(session.getTitle())
                .status(session.getStatus())
                .enabledLanguages(enabledLanguages)
                .build();
    }

    private List<String> parseEnabledLanguages(String enabledLanguages) {
        if (enabledLanguages == null || enabledLanguages.isBlank()) {
            return List.of("zh-CN", "id-ID");
        }
        return java.util.Arrays.stream(enabledLanguages.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public InterpretationSessionVo getSessionHistory(String sessionId) {
        InterpretationSession session = sessionService.getSessionHistory(sessionId);
        if (session == null) return null;
        return toVo(session);
    }

    public List<InterpretationSessionVo> searchUserSessions(Long userId, String keyword) {
        log.info("[InterpretationFacade] searchUserSessions, userId={}, keyword={}", userId, keyword);
        return sessionService.searchUserSessions(userId, keyword).stream()
                .map(this::toVo)
                .toList();
    }

    public List<InterpretationSessionVo> getSessionsByMeeting(Long meetingId) {
        return sessionService.findByMeetingId(meetingId).stream()
                .map(this::toVo)
                .toList();
    }

    public boolean updateTitle(String sessionId, Long userId, String title) {
        log.info("[InterpretationFacade] updateTitle, sessionId={}, userId={}", sessionId, userId);
        return sessionService.updateTitle(sessionId, userId, title);
    }

    public boolean deleteSession(String sessionId, Long userId) {
        log.info("[InterpretationFacade] deleteSession, sessionId={}, userId={}", sessionId, userId);
        return sessionService.deleteSession(sessionId, userId);
    }

    public InterpretationResultItemVo saveResult(SaveInterpretationResultRequest request) {
        log.info("[InterpretationFacade] saveResult start, sessionId={}", request.getSessionId());
        InterpretationResultItemVo result = resultService.save(request);
        result.setMeetingTitle(resolveSessionTitle(request.getSessionId()));
        log.info("[InterpretationFacade] saveResult end, sessionId={}, resultId={}",
                request.getSessionId(), result.getId());
        return result;
    }

    public List<InterpretationResultItemVo> listPublicResults(String sessionId) {
        log.info("[InterpretationFacade] listPublicResults start, sessionId={}", sessionId);
        String meetingTitle = resolveSessionTitle(sessionId);
        List<InterpretationResultItemVo> results = resultService.listBySessionId(sessionId);
        results.forEach(result -> result.setMeetingTitle(meetingTitle));
        log.info("[InterpretationFacade] listPublicResults end, sessionId={}, count={}", sessionId, results.size());
        return results;
    }

    public List<InterpretationRecordVo> getRecords(String sessionId) {
        log.info("[InterpretationFacade] getRecords start, sessionId={}", sessionId);
        String meetingTitle = resolveSessionTitle(sessionId);
        List<InterpretationRecordVo> records = recordService.getSessionRecords(sessionId).stream()
                .map(this::toVo)
                .toList();
        records.forEach(record -> record.setMeetingTitle(meetingTitle));
        log.info("[InterpretationFacade] getRecords end, sessionId={}, count={}", sessionId, records.size());
        return records;
    }

    public List<SessionSpeakerMappingVo> getSessionSpeakerMappings(String sessionId) {
        log.info("[InterpretationFacade] getSessionSpeakerMappings, sessionId={}", sessionId);
        Map<String, String> mappings = sessionSpeakerNameService.getSessionMappings(sessionId);
        return mappings.entrySet().stream()
                .map(e -> new SessionSpeakerMappingVo(e.getKey(), e.getValue()))
                .toList();
    }

    public SessionSpeakerMappingVo mapSessionSpeaker(String sessionId, String speakerId, String personName) {
        log.info("[InterpretationFacade] mapSessionSpeaker, sessionId={}, speakerId={}, personName={}",
                sessionId, speakerId, personName);
        sessionSpeakerNameService.bulkRename(sessionId, speakerId, personName);
        return new SessionSpeakerMappingVo(speakerId, personName);
    }

    private InterpretationSessionVo toVo(InterpretationSession session) {
        return InterpretationSessionVo.builder()
                .sessionId(session.getSessionId())
                .sourceLang(session.getSourceLang())
                .targetLang(session.getTargetLang())
                .title(session.getTitle())
                .status(session.getStatus())
                .voiceId(session.getVoiceId())
                .startTime(session.getStartTime() != null ? session.getStartTime().toString() : null)
                .endTime(session.getEndTime() != null ? session.getEndTime().toString() : null)
                .resultCount(session.getResultCount())
                .asrAudioMs(session.getAsrAudioMs())
                .translateChars(session.getTranslateChars())
                .ttsChars(session.getTtsChars())
                .llmInputTokens(session.getLlmInputTokens())
                .llmOutputTokens(session.getLlmOutputTokens())
                .llmSummaryInputTokens(session.getLlmSummaryInputTokens())
                .llmSummaryOutputTokens(session.getLlmSummaryOutputTokens())
                .meetingSummary(session.getMeetingSummary())
                .build();
    }

    private String resolveSessionTitle(String sessionId) {
        return sessionService.getSession(sessionId)
                .map(InterpretationSession::getTitle)
                .orElse(null);
    }

    private InterpretationRecordVo toVo(InterpretationRecord record) {
        return InterpretationRecordVo.builder()
                .id(record.getId())
                .sessionId(record.getSessionId())
                .seq(record.getSeq())
                .sourceLang(record.getSourceLang())
                .targetLang(record.getTargetLang())
                .sourceText(record.getSourceText())
                .targetText(record.getTargetText())
                .spokenAt(record.getSpokenAt())
                .createTime(record.getCreateTime())
                .build();
    }
}
