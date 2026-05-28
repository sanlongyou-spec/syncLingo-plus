package com.si.backend.facade;

import com.si.backend.dto.SaveInterpretationResultRequest;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.InterpretationRecord;
import com.si.backend.entity.SessionSpeakerVoice;
import com.si.backend.entity.VoiceUsageRecord;
import com.si.backend.dto.StartInterpretationRequest;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationResultService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.MeetingSummaryService;
import com.si.backend.service.SessionSpeakerVoiceService;
import com.si.backend.service.SpeakerIdentityService;
import com.si.backend.service.UserLanguagePreferenceService;
import com.si.backend.service.VoiceUsageRecordService;
import java.util.concurrent.CompletableFuture;
import com.si.backend.vo.InterpretationRecordVo;
import com.si.backend.vo.InterpretationResultItemVo;
import com.si.backend.vo.InterpretationSessionVo;
import com.si.backend.vo.SessionSpeakerVoiceVo;
import com.si.backend.vo.SessionSpeakerIdentityVo;
import com.si.backend.vo.SpeakerIdentityVo;
import com.si.backend.vo.VoiceUsageRecordVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;

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
    private final VoiceUsageRecordService voiceUsageRecordService;
    private final SessionSpeakerVoiceService sessionSpeakerVoiceService;
    private final SpeakerIdentityService speakerIdentityService;
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

    public java.util.Map<String, Object> stopInterpretation(String sessionId) {
        log.info("[InterpretationFacade] stopInterpretation, sessionId={}", sessionId);
        java.util.Map<String, Object> result = sessionService.stopSession(sessionId);
        CompletableFuture.runAsync(() -> meetingSummaryService.generateAndSaveAsync(sessionId));
        return result;
    }

    public InterpretationSessionVo getSessionStatus(String sessionId) {
        return sessionService.getSession(sessionId)
                .map(this::toVo)
                .orElse(null);
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

    public List<VoiceUsageRecordVo> getVoiceUsage(String sessionId) {
        log.info("[InterpretationFacade] getVoiceUsage start, sessionId={}", sessionId);
        String meetingTitle = resolveSessionTitle(sessionId);
        List<VoiceUsageRecordVo> records = voiceUsageRecordService.getSessionUsage(sessionId).stream()
                .map(this::toVo)
                .toList();
        records.forEach(record -> record.setMeetingTitle(meetingTitle));
        log.info("[InterpretationFacade] getVoiceUsage end, sessionId={}, count={}", sessionId, records.size());
        return records;
    }

    public List<SessionSpeakerVoiceVo> getSpeakerVoices(String sessionId) {
        log.info("[InterpretationFacade] getSpeakerVoices start, sessionId={}", sessionId);
        List<SessionSpeakerVoiceVo> voices = sessionSpeakerVoiceService.getSessionSpeakerVoices(sessionId).stream()
                .map(this::toVo)
                .toList();
        log.info("[InterpretationFacade] getSpeakerVoices end, sessionId={}, count={}", sessionId, voices.size());
        return voices;
    }

    public List<SessionSpeakerIdentityVo> getSessionSpeakerIdentities(String sessionId) {
        log.info("[InterpretationFacade] getSessionSpeakerIdentities start, sessionId={}", sessionId);
        List<SessionSpeakerIdentityVo> identities = speakerIdentityService.listSessionMappings(sessionId);
        log.info("[InterpretationFacade] getSessionSpeakerIdentities end, sessionId={}, count={}",
                sessionId, identities.size());
        return identities;
    }

    public SessionSpeakerIdentityVo mapSessionSpeaker(String sessionId, String speakerId, String personName) {
        log.info("[InterpretationFacade] mapSessionSpeaker start, sessionId={}, speakerId={}, personName={}",
                sessionId, speakerId, personName);
        SessionSpeakerIdentityVo identity = speakerIdentityService.mapSessionSpeaker(sessionId, speakerId, personName);
        // If auto-clone already completed before the user mapped this speaker, bind the voiceId now.
        com.si.backend.entity.SessionSpeakerVoice readyVoice =
                sessionSpeakerVoiceService.findReadyVoice(sessionId, speakerId);
        if (readyVoice != null) {
            speakerIdentityService.bindCartesiaVoiceFromSessionSpeaker(
                    sessionId, speakerId, readyVoice.getCartesiaVoiceId(), readyVoice.getLanguage());
            log.info("[InterpretationFacade] bound existing cloned voice on mapping, sessionId={}, speakerId={}, voiceId={}",
                    sessionId, speakerId, readyVoice.getCartesiaVoiceId());
        }
        // Auto-enroll in speaker recognition service using accumulated session audio.
        byte[] speakerPcm = sessionSpeakerVoiceService.getSpeakerAudioPcm(sessionId, speakerId);
        // 16000 Hz * 1 channel * 2 bytes/sample * 2 seconds = 64000 bytes minimum
        int minEnrollBytes = 16000 * 2 * 2;
        if (speakerPcm.length >= minEnrollBytes) {
            SpeakerIdentityVo identityRecord =
                    speakerIdentityService.findIdentityByName(personName.trim());
            if (identityRecord != null && identityRecord.getId() != null
                    && (identityRecord.getSpeakerProfileId() == null
                        || identityRecord.getSpeakerProfileId().isBlank())) {
                String locale = readyVoice != null ? readyVoice.getLanguage() : "zh";
                final Long identityId = identityRecord.getId();
                final byte[] pcmSnapshot = speakerPcm;
                final String finalLocale = locale;
                CompletableFuture.runAsync(() -> {
                    try {
                        speakerIdentityService.enrollSpeakerProfile(identityId, pcmSnapshot, finalLocale);
                        log.info("[InterpretationFacade] auto-enrolled speaker from session audio, " +
                                "sessionId={}, speakerId={}, personName={}, bytes={}",
                                sessionId, speakerId, personName, pcmSnapshot.length);
                    } catch (Exception e) {
                        log.warn("[InterpretationFacade] auto-enroll failed, speakerId={}, reason={}",
                                speakerId, e.getMessage());
                    }
                });
            }
        }
        log.info("[InterpretationFacade] mapSessionSpeaker end, sessionId={}, speakerId={}", sessionId, speakerId);
        return identity;
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

    private VoiceUsageRecordVo toVo(VoiceUsageRecord record) {
        return VoiceUsageRecordVo.builder()
                .id(record.getId())
                .sessionId(record.getSessionId())
                .userId(record.getUserId())
                .voiceId(record.getVoiceId())
                .targetLang(record.getTargetLang())
                .textLen(record.getTextLen())
                .usedAt(record.getUsedAt())
                .build();
    }

    private SessionSpeakerVoiceVo toVo(SessionSpeakerVoice voice) {
        return SessionSpeakerVoiceVo.builder()
                .id(voice.getId())
                .sessionId(voice.getSessionId())
                .speakerId(voice.getSpeakerId())
                .cartesiaVoiceId(voice.getCartesiaVoiceId())
                .cloneStatus(voice.getCloneStatus())
                .audioSeconds(voice.getAudioSeconds())
                .language(voice.getLanguage())
                .errorMessage(voice.getErrorMessage())
                .createTime(voice.getCreateTime())
                .updateTime(voice.getUpdateTime())
                .build();
    }
}
