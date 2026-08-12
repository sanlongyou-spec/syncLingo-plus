package com.si.backend.facade;

import com.si.backend.dto.SaveInterpretationResultRequest;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.entity.InterpretationRecord;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationResultService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.MeetingSummaryService;
import com.si.backend.service.ResourceOwnershipPolicy;
import com.si.backend.service.SessionSpeakerNameService;
import com.si.backend.service.UserLanguagePreferenceService;
import com.si.backend.util.AuthContext;
import com.si.backend.vo.InterpretationRecordVo;
import com.si.backend.vo.InterpretationResultItemVo;
import com.si.backend.vo.InterpretationSessionVo;
import com.si.backend.vo.PublicSessionInfoVo;
import com.si.backend.vo.SessionSpeakerMappingVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Coordinates interpretation session operations and enforces session ownership.
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
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;

    @Transactional
    public String startInterpretation(AuthenticatedActor actor, StartInterpretationRequest request) {
        Long userId = actor.userId();
        if (request.getMeetingId() != null) {
            resourceOwnershipPolicy.requireOwnedMeeting(actor, request.getMeetingId());
        }
        log.info("[InterpretationFacade] startInterpretation start, userId={}, sourceLang={}, targetLang={}, voiceId={}",
                userId, request.getSourceLang(), request.getTargetLang(), request.getVoiceId());
        String sessionId = UUID.randomUUID().toString();
        List<String> enabledLanguages = userLanguagePreferenceService.resolveEnabledLanguages(
                userId,
                request.getEnabledLanguages()
        );
        sessionService.startSession(
                sessionId,
                userId,
                request.getSourceLang(),
                request.getTargetLang(),
                request.getTitle(),
                request.getVoiceId(),
                request.getHotwordIds(),
                enabledLanguages,
                request.getMeetingId()
        );
        log.info("[InterpretationFacade] startInterpretation end, sessionId={}", sessionId);
        return sessionId;
    }

    public Map<String, Object> stopInterpretation(AuthenticatedActor actor, String sessionId) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[InterpretationFacade] stopInterpretation start, sessionId={}", sessionId);
        Map<String, Object> result = sessionService.stopSession(sessionId);
        CompletableFuture.runAsync(() -> meetingSummaryService.generateAndSaveAsync(sessionId));
        log.info("[InterpretationFacade] stopInterpretation end, sessionId={}", sessionId);
        return result;
    }

    public InterpretationSessionVo getSessionStatus(AuthenticatedActor actor, String sessionId) {
        return toVo(resourceOwnershipPolicy.requireOwnedSession(actor, sessionId));
    }

    public InterpretationSessionVo getSessionHistory(AuthenticatedActor actor, String sessionId) {
        return toVo(resourceOwnershipPolicy.requireOwnedSession(actor, sessionId));
    }

    public List<InterpretationSessionVo> searchUserSessions(Long userId, String keyword) {
        log.info("[InterpretationFacade] searchUserSessions start, userId={}, keyword={}", userId, keyword);
        List<InterpretationSessionVo> sessions = sessionService.searchUserSessions(userId, keyword).stream()
                .map(this::toVo)
                .toList();
        log.info("[InterpretationFacade] searchUserSessions end, userId={}, count={}", userId, sessions.size());
        return sessions;
    }

    public List<InterpretationSessionVo> getSessionsByMeeting(AuthenticatedActor actor, Long meetingId) {
        resourceOwnershipPolicy.requireOwnedMeeting(actor, meetingId);
        return sessionService.findByMeetingId(meetingId).stream()
                .map(this::toVo)
                .toList();
    }

    public boolean updateTitle(AuthenticatedActor actor, String sessionId, String title) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[InterpretationFacade] updateTitle start, sessionId={}, userId={}", sessionId, actor.userId());
        boolean updated = sessionService.updateTitle(sessionId, actor.userId(), title);
        log.info("[InterpretationFacade] updateTitle end, sessionId={}, updated={}", sessionId, updated);
        return updated;
    }

    public boolean deleteSession(AuthenticatedActor actor, String sessionId) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[InterpretationFacade] deleteSession start, sessionId={}, userId={}", sessionId, actor.userId());
        boolean deleted = sessionService.deleteSession(sessionId, actor.userId());
        log.info("[InterpretationFacade] deleteSession end, sessionId={}, deleted={}", sessionId, deleted);
        return deleted;
    }

    public InterpretationResultItemVo saveResult(
            AuthenticatedActor actor,
            SaveInterpretationResultRequest request
    ) {
        resourceOwnershipPolicy.requireOwnedSession(actor, request.getSessionId());
        log.info("[InterpretationFacade] saveResult start, sessionId={}", request.getSessionId());
        InterpretationResultItemVo result = resultService.save(request);
        result.setMeetingTitle(resolveSessionTitle(request.getSessionId()));
        log.info("[InterpretationFacade] saveResult end, sessionId={}, resultId={}",
                request.getSessionId(), result.getId());
        return result;
    }

    public List<InterpretationRecordVo> getRecords(AuthenticatedActor actor, String sessionId) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[InterpretationFacade] getRecords start, sessionId={}", sessionId);
        String meetingTitle = resolveSessionTitle(sessionId);
        List<InterpretationRecordVo> records = recordService.getSessionRecords(sessionId).stream()
                .map(this::toVo)
                .toList();
        records.forEach(record -> record.setMeetingTitle(meetingTitle));
        log.info("[InterpretationFacade] getRecords end, sessionId={}, count={}", sessionId, records.size());
        return records;
    }

    public List<SessionSpeakerMappingVo> getSessionSpeakerMappings(
            AuthenticatedActor actor,
            String sessionId
    ) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[InterpretationFacade] getSessionSpeakerMappings start, sessionId={}", sessionId);
        List<SessionSpeakerMappingVo> mappings = sessionSpeakerNameService.getSessionMappings(sessionId)
                .entrySet()
                .stream()
                .map(entry -> new SessionSpeakerMappingVo(entry.getKey(), entry.getValue()))
                .toList();
        log.info("[InterpretationFacade] getSessionSpeakerMappings end, sessionId={}, count={}",
                sessionId, mappings.size());
        return mappings;
    }

    public SessionSpeakerMappingVo mapSessionSpeaker(
            AuthenticatedActor actor,
            String sessionId,
            String speakerId,
            String personName
    ) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[InterpretationFacade] mapSessionSpeaker start, sessionId={}, speakerId={}", sessionId, speakerId);
        sessionSpeakerNameService.bulkRename(sessionId, speakerId, personName);
        log.info("[InterpretationFacade] mapSessionSpeaker end, sessionId={}, speakerId={}", sessionId, speakerId);
        return new SessionSpeakerMappingVo(speakerId, personName);
    }

    public String getActiveSessionIdForUser(Long userId) {
        return sessionService.getActiveSessionForUser(userId)
                .map(InterpretationSession::getSessionId)
                .orElse(null);
    }

    public PublicSessionInfoVo getPublicSessionInfo(String sessionId) {
        InterpretationSession session = sessionService.getSession(sessionId)
                .orElseGet(() -> sessionService.getSessionHistory(sessionId));
        if (session == null) {
            return null;
        }
        return PublicSessionInfoVo.builder()
                .sessionId(sessionId)
                .title(session.getTitle())
                .status(session.getStatus())
                .enabledLanguages(parseEnabledLanguages(session.getEnabledLanguages()))
                .build();
    }

    public List<InterpretationResultItemVo> listPublicResults(String sessionId) {
        log.info("[InterpretationFacade] listPublicResults start, sessionId={}", sessionId);
        String meetingTitle = resolveSessionTitle(sessionId);
        List<InterpretationResultItemVo> results = resultService.listBySessionId(sessionId);
        results.forEach(result -> result.setMeetingTitle(meetingTitle));
        log.info("[InterpretationFacade] listPublicResults end, sessionId={}, count={}", sessionId, results.size());
        return results;
    }

    public List<InterpretationResultItemVo> listPublicResults(String sessionId, Long afterId, Integer limit) {
        if (afterId == null) {
            return listPublicResults(sessionId);
        }
        log.info("[InterpretationFacade] listPublicResults incremental start, sessionId={}, afterId={}, limit={}",
                sessionId, afterId, limit);
        String meetingTitle = resolveSessionTitle(sessionId);
        List<InterpretationResultItemVo> results = resultService.listBySessionIdAfterId(sessionId, afterId, limit);
        results.forEach(result -> result.setMeetingTitle(meetingTitle));
        log.info("[InterpretationFacade] listPublicResults incremental end, sessionId={}, afterId={}, limit={}, count={}",
                sessionId, afterId, limit, results.size());
        return results;
    }

    private List<String> parseEnabledLanguages(String enabledLanguages) {
        if (enabledLanguages == null || enabledLanguages.isBlank()) {
            return List.of("zh-CN", "id-ID");
        }
        return Arrays.stream(enabledLanguages.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
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
