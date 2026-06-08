package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.dto.SaveSpeakerIdentityRequest;
import com.si.backend.entity.SpeakerIdentity;
import com.si.backend.integration.SpeakerServiceIntegration;
import com.si.backend.integration.SpeakerServiceIntegration.IdentifyResult;
import com.si.backend.mapper.SpeakerIdentityMapper;
import com.si.backend.vo.SessionSpeakerIdentityVo;
import com.si.backend.vo.SpeakerIdentityVo;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves ASR speaker ids to persistent speaker identities via the self-hosted Pyannote speaker service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpeakerIdentityService {

    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String STATUS_IDENTIFIED = "IDENTIFIED";
    private static final String STATUS_MANUAL = "MANUAL";
    private static final String SOURCE_SPEAKER_SERVICE = "SPEAKER_SERVICE";
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final String SOURCE_UNKNOWN = "UNKNOWN";
    private static final String SOURCE_INHERITED = "INHERITED";


    private final SpeakerIdentityMapper mapper;
    private final SpeakerServiceIntegration speakerServiceIntegration;
    private final com.si.backend.config.SpeakerServiceProperties speakerServiceProperties;

    private final Map<String, SessionSpeakerIdentity> sessionIdentityMap = new ConcurrentHashMap<>();
    /** Per-stream (sessionId:speakerId) last-accepted speaker + score, for switch hysteresis. */
    private final Map<String, ResolvedScore> hysteresisMap = new ConcurrentHashMap<>();
    /** Per-session last successfully-resolved speaker, used to attribute Azure "Unknown" segments. */
    private final Map<String, SessionSpeakerIdentity> sessionLastResolvedMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void initTable() {
        log.info("[SpeakerIdentityService] initTable start");
        mapper.createTableIfNotExists();
        log.info("[SpeakerIdentityService] initTable end");
    }

    public void startSession(String sessionId) {
        log.info("[SpeakerIdentityService] startSession, sessionId={}", sessionId);
    }

    public void cleanupSession(String sessionId) {
        log.info("[SpeakerIdentityService] cleanupSession, sessionId={}", sessionId);
        sessionIdentityMap.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        hysteresisMap.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        sessionLastResolvedMap.remove(sessionId);
    }

    /**
     * Switch hysteresis: once a stream (sessionId:speakerId) has a resolved speaker,
     * a differing identification only replaces it when the new score is high-confidence
     * (>= switchScore). Low-confidence differing readings are treated as brief
     * mid-speech misidentifications and the current speaker is kept.
     *
     * @return the speaker name to actually use after hysteresis.
     */
    private String applyHysteresis(String sessionId, String speakerId, String candidateName, double score, double margin) {
        String key = buildKey(sessionId, speakerId);
        ResolvedScore incumbent = hysteresisMap.get(key);
        if (incumbent == null || incumbent.personName().equals(candidateName)) {
            hysteresisMap.put(key, new ResolvedScore(candidateName, score));
            return candidateName;
        }
        double switchScore = speakerServiceProperties.getSwitchScore() != null
                ? speakerServiceProperties.getSwitchScore() : 0.45D;
        double marginThreshold = speakerServiceProperties.getMarginThreshold() != null
                ? speakerServiceProperties.getMarginThreshold() : 0.06D;
        // Switch only when high-confidence AND unambiguous (clear gap over the runner-up).
        if (score >= switchScore && margin >= marginThreshold) {
            log.info("[SpeakerIdentityService] hysteresis switch, sessionId={}, speakerId={}, {}(s={}) -> {}(s={}, margin={})",
                    sessionId, speakerId, incumbent.personName(), incumbent.score(), candidateName, score, margin);
            hysteresisMap.put(key, new ResolvedScore(candidateName, score));
            return candidateName;
        }
        log.info("[SpeakerIdentityService] hysteresis kept incumbent, sessionId={}, speakerId={}, keep={}(s={}), rejected={}(s={}, margin={}), switchScore={}, marginThreshold={}",
                sessionId, speakerId, incumbent.personName(), incumbent.score(), candidateName, score, margin, switchScore, marginThreshold);
        return incumbent.personName();
    }

    public SpeakerIdentityVo saveIdentity(Long id, SaveSpeakerIdentityRequest request) {
        log.info("[SpeakerIdentityService] saveIdentity start, id={}, personName={}", id, request.getPersonName());
        SpeakerIdentity identity = new SpeakerIdentity();
        identity.setId(id);
        identity.setPersonName(normalize(request.getPersonName()));
        identity.setSpeakerProfileId(blankToNull(request.getSpeakerProfileId()));
        identity.setCartesiaVoiceId(blankToNull(request.getCartesiaVoiceId()));
        identity.setLanguage(blankToNull(request.getLanguage()));
        identity.setNote(blankToNull(request.getNote()));
        if (id == null) {
            SpeakerIdentity existing = mapper.findByPersonName(identity.getPersonName());
            if (existing != null) {
                identity.setId(existing.getId());
                mapper.update(identity);
            } else {
                mapper.insert(identity);
            }
        } else {
            mapper.update(identity);
        }
        SpeakerIdentity saved = identity.getId() != null ? mapper.findById(identity.getId()) : mapper.findByPersonName(identity.getPersonName());
        log.info("[SpeakerIdentityService] saveIdentity end, id={}", saved != null ? saved.getId() : null);
        return toVo(saved);
    }

    public List<SpeakerIdentityVo> listIdentities() {
        log.info("[SpeakerIdentityService] listIdentities start");
        List<SpeakerIdentityVo> result = mapper.findAll().stream()
                .map(this::toVo)
                .toList();
        log.info("[SpeakerIdentityService] listIdentities end, count={}", result.size());
        return result;
    }

    public void deleteIdentity(Long id) {
        log.info("[SpeakerIdentityService] deleteIdentity start, id={}", id);
        SpeakerIdentity identity = mapper.findById(id);
        mapper.deleteById(id);
        boolean embeddingRemoved = false;
        if (identity != null && identity.getPersonName() != null) {
            embeddingRemoved = speakerServiceIntegration.deleteEnrollment(identity.getPersonName());
        }
        log.info("[SpeakerIdentityService] deleteIdentity end, id={}, personName={}, embeddingRemoved={}",
                id, identity != null ? identity.getPersonName() : null, embeddingRemoved);
    }

    /** Auto-enrolls a speaker into the Pyannote service using raw PCM audio from the current session. */
    public SpeakerIdentityVo enrollSpeakerProfile(Long id, byte[] audioBytes, String locale) {
        log.info("[SpeakerIdentityService] enrollSpeakerProfile start, id={}, audioBytes={}", id, audioBytes != null ? audioBytes.length : 0);
        SpeakerIdentity identity = mapper.findById(id);
        if (identity == null) {
            throw new IllegalArgumentException("Speaker identity not found: " + id);
        }
        if (!speakerServiceIntegration.isEnabled()) {
            log.warn("[SpeakerIdentityService] enrollSpeakerProfile skipped, speaker service not enabled, id={}", id);
            return toVo(identity);
        }
        String name = identity.getPersonName();
        int count = speakerServiceIntegration.enroll(name, audioBytes, true);
        if (count < 0) {
            throw new RuntimeException("Speaker service enrollment failed for: " + name);
        }
        if (identity.getSpeakerProfileId() == null || identity.getSpeakerProfileId().isBlank()) {
            // Use personName as the profile ID — speaker service keys embeddings by name,
            // so name is both unique and meaningful. The old ENROLLED_MARKER constant string
            // caused uk_speaker_profile_id constraint violations when multiple speakers enrolled.
            mapper.updateProfileId(id, normalize(name));
        }
        log.info("[SpeakerIdentityService] enrollSpeakerProfile done, id={}, name={}, embeddingCount={}", id, name, count);
        return toVo(mapper.findById(id));
    }

    public SpeakerResolution resolveOrIdentify(String sessionId, String speakerId, byte[] recentPcm) {
        if (!hasSpeakerId(speakerId)) {
            return SpeakerResolution.unknown(sessionId, speakerId);
        }
        boolean transientUnknownSpeaker = isUnknownSpeakerId(speakerId);
        String key = buildKey(sessionId, speakerId);
        if (!transientUnknownSpeaker) {
            SessionSpeakerIdentity existing = sessionIdentityMap.get(key);
            if (existing != null && !STATUS_UNKNOWN.equals(existing.getStatus())) {
                return existing.toResolution();
            }
        }
        if (!speakerServiceIntegration.isEnabled()) {
            SessionSpeakerIdentity unknown = unknownOutcome(sessionId, speakerId, transientUnknownSpeaker);
            if (!transientUnknownSpeaker) {
                sessionIdentityMap.putIfAbsent(key, unknown);
            }
            return unknown.toResolution();
        }

        List<SpeakerIdentity> candidates = mapper.findAllWithSpeakerProfile();
        if (candidates.isEmpty() || recentPcm == null || recentPcm.length == 0) {
            SessionSpeakerIdentity unknown = unknownOutcome(sessionId, speakerId, transientUnknownSpeaker);
            if (!transientUnknownSpeaker) {
                sessionIdentityMap.putIfAbsent(key, unknown);
            }
            return unknown.toResolution();
        }

        List<String> names = candidates.stream()
                .map(SpeakerIdentity::getPersonName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        Optional<IdentifyResult> ssResult = speakerServiceIntegration.identify(recentPcm, names);
        if (ssResult.isPresent()) {
            String candidateName = ssResult.get().getPersonName();
            double score = ssResult.get().getScore() != null ? ssResult.get().getScore() : 0d;
            double margin = ssResult.get().getMargin() != null ? ssResult.get().getMargin() : 1d;
            String personName = applyHysteresis(sessionId, speakerId, candidateName, score, margin);
            SpeakerIdentity identity = mapper.findByPersonName(personName);
            if (identity != null) {
                SessionSpeakerIdentity resolved = SessionSpeakerIdentity.builder()
                        .sessionId(sessionId)
                        .speakerId(speakerId)
                        .personName(identity.getPersonName())
                        .speakerProfileId(identity.getSpeakerProfileId())
                        .cartesiaVoiceId(identity.getCartesiaVoiceId())
                        .status(STATUS_IDENTIFIED)
                        .source(SOURCE_SPEAKER_SERVICE)
                        .build();
                if (!transientUnknownSpeaker) {
                    sessionIdentityMap.put(key, resolved);
                }
                sessionLastResolvedMap.put(sessionId, resolved);
                log.info("[SpeakerIdentityService] speaker identified, sessionId={}, speakerId={}, personName={}, candidate={}, score={}",
                        sessionId, speakerId, personName, candidateName, score);
                return resolved.toResolution();
            }
        }
        SessionSpeakerIdentity unknown = unknownOutcome(sessionId, speakerId, transientUnknownSpeaker);
        if (!transientUnknownSpeaker) {
            sessionIdentityMap.putIfAbsent(key, unknown);
        }
        return unknown.toResolution();
    }

    /**
     * Outcome when a speaker could not be identified. For an Azure "Unknown" segment
     * (diarization could not attribute it — common in real-time), attribute it to the
     * last confirmed speaker of the session instead of leaving it nameless, since such
     * segments are almost always the current speaker continuing. Voiceprint is still
     * tried first; this only applies when it cannot decide.
     */
    private SessionSpeakerIdentity unknownOutcome(String sessionId, String speakerId, boolean transientUnknownSpeaker) {
        if (transientUnknownSpeaker) {
            SessionSpeakerIdentity last = sessionLastResolvedMap.get(sessionId);
            if (last != null && last.getPersonName() != null && !last.getPersonName().isBlank()) {
                log.info("[SpeakerIdentityService] Unknown segment inherits last confirmed speaker, sessionId={}, personName={}",
                        sessionId, last.getPersonName());
                return SessionSpeakerIdentity.builder()
                        .sessionId(sessionId)
                        .speakerId(speakerId)
                        .personName(last.getPersonName())
                        .speakerProfileId(last.getSpeakerProfileId())
                        .cartesiaVoiceId(last.getCartesiaVoiceId())
                        .status(STATUS_IDENTIFIED)
                        .source(SOURCE_INHERITED)
                        .build();
            }
        }
        return unknownSessionIdentity(sessionId, speakerId);
    }

    public SessionSpeakerIdentityVo mapSessionSpeaker(String sessionId, String speakerId, String personName) {
        log.info("[SpeakerIdentityService] mapSessionSpeaker start, sessionId={}, speakerId={}, personName={}",
                sessionId, speakerId, personName);
        String normalizedName = normalize(personName);
        SpeakerIdentity identity = mapper.findByPersonName(normalizedName);
        if (identity == null) {
            identity = new SpeakerIdentity();
            identity.setPersonName(normalizedName);
            mapper.insert(identity);
            log.info("[SpeakerIdentityService] mapSessionSpeaker: persisted new SpeakerIdentity, personName={}", normalizedName);
        }
        SessionSpeakerIdentity mapped = SessionSpeakerIdentity.builder()
                .sessionId(sessionId)
                .speakerId(speakerId)
                .personName(normalizedName)
                .speakerProfileId(identity.getSpeakerProfileId())
                .cartesiaVoiceId(identity.getCartesiaVoiceId())
                .status(STATUS_MANUAL)
                .source(SOURCE_MANUAL)
                .build();
        sessionIdentityMap.put(buildKey(sessionId, speakerId), mapped);
        sessionLastResolvedMap.put(sessionId, mapped);
        log.info("[SpeakerIdentityService] mapSessionSpeaker end, sessionId={}, speakerId={}, personName={}",
                sessionId, speakerId, normalizedName);
        return toVo(mapped);
    }

    public List<SessionSpeakerIdentityVo> listSessionMappings(String sessionId) {
        return sessionIdentityMap.values().stream()
                .filter(item -> sessionId.equals(item.getSessionId()))
                .sorted(Comparator.comparing(SessionSpeakerIdentity::getSpeakerId))
                .map(this::toVo)
                .toList();
    }

    public String resolveVoiceId(String sessionId, String speakerId) {
        if (!hasSpeakerId(speakerId) || isUnknownSpeakerId(speakerId)) {
            return null;
        }
        SessionSpeakerIdentity mapping = sessionIdentityMap.get(buildKey(sessionId, speakerId));
        return mapping != null ? mapping.getCartesiaVoiceId() : null;
    }

    public SpeakerIdentityVo findIdentityByName(String personName) {
        return toVo(mapper.findByPersonName(normalize(personName)));
    }

    public String getCachedSpeakerName(String sessionId, String speakerId) {
        if (!hasSpeakerId(speakerId) || isUnknownSpeakerId(speakerId)) return null;
        SessionSpeakerIdentity existing = sessionIdentityMap.get(buildKey(sessionId, speakerId));
        if (existing != null && existing.getPersonName() != null && !existing.getPersonName().isBlank()) {
            return existing.getPersonName();
        }
        return null;
    }

    @Transactional
    public void bindCartesiaVoiceFromSessionSpeaker(String sessionId, String speakerId, String cartesiaVoiceId, String language) {
        if (cartesiaVoiceId == null || cartesiaVoiceId.isBlank()) {
            return;
        }
        SessionSpeakerIdentity mapping = sessionIdentityMap.get(buildKey(sessionId, speakerId));
        if (mapping == null || mapping.getPersonName() == null || mapping.getPersonName().isBlank()) {
            return;
        }
        SpeakerIdentity identity = mapper.findByPersonName(mapping.getPersonName());
        if (identity == null) {
            identity = new SpeakerIdentity();
            identity.setPersonName(mapping.getPersonName());
            identity.setSpeakerProfileId(mapping.getSpeakerProfileId());
            identity.setCartesiaVoiceId(cartesiaVoiceId);
            identity.setLanguage(language);
            mapper.insert(identity);
        } else if (identity.getCartesiaVoiceId() == null || identity.getCartesiaVoiceId().isBlank()) {
            identity.setCartesiaVoiceId(cartesiaVoiceId);
            identity.setLanguage(language);
            mapper.updateVoiceIfBlank(identity);
        }
        mapping.setCartesiaVoiceId(cartesiaVoiceId);
        sessionIdentityMap.put(buildKey(sessionId, speakerId), mapping);
        log.info("[SpeakerIdentityService] cartesia voice bound via session map, personName={}, voiceId={}",
                mapping.getPersonName(), cartesiaVoiceId);
    }

    public void bindCartesiaVoiceDirect(String personName, String cartesiaVoiceId, String language) {
        if (personName == null || personName.isBlank() || cartesiaVoiceId == null || cartesiaVoiceId.isBlank()) {
            return;
        }
        SpeakerIdentity identity = mapper.findByPersonName(normalize(personName));
        if (identity == null) {
            return;
        }
        if (identity.getCartesiaVoiceId() == null || identity.getCartesiaVoiceId().isBlank()) {
            identity.setCartesiaVoiceId(cartesiaVoiceId);
            identity.setLanguage(language);
            mapper.updateVoiceIfBlank(identity);
            log.info("[SpeakerIdentityService] cartesia voice bound directly, personName={}, voiceId={}", personName, cartesiaVoiceId);
        }
        // 同步内存中的会话映射，否则前端会话列表会一直显示"克隆完成后自动绑定"（声音其实已绑定）。
        String norm = normalize(personName);
        for (SessionSpeakerIdentity mapping : sessionIdentityMap.values()) {
            if (mapping.getPersonName() != null
                    && norm.equals(normalize(mapping.getPersonName()))
                    && (mapping.getCartesiaVoiceId() == null || mapping.getCartesiaVoiceId().isBlank())) {
                mapping.setCartesiaVoiceId(cartesiaVoiceId);
            }
        }
    }

    public void enrollByPersonName(String personName, byte[] pcmBytes, String language) {
        if (personName == null || personName.isBlank() || pcmBytes == null || pcmBytes.length == 0) {
            return;
        }
        SpeakerIdentity identity = mapper.findByPersonName(normalize(personName));
        if (identity == null || identity.getId() == null) {
            return;
        }
        if (identity.getSpeakerProfileId() != null && !identity.getSpeakerProfileId().isBlank()) {
            log.debug("[SpeakerIdentityService] skip enroll, already enrolled, personName={}", personName);
            return;
        }
        enrollSpeakerProfile(identity.getId(), pcmBytes, language);
    }

    /**
     * Enroll a person from accumulated PCM by splitting it into multiple samples (multi-sample →
     * 更稳的质心、更准的识别）。只在该人尚未注册过时执行（profileId 守卫，避免两条路径重复注册）。
     * 两条入口（手动绑定 InterpretationFacade / 会中自动 SessionSpeakerVoiceService）都走这里，统一切分。
     */
    public void enrollSamplesByPersonName(String personName, byte[] fullPcm, String language) {
        if (personName == null || personName.isBlank() || fullPcm == null || fullPcm.length == 0) {
            return;
        }
        SpeakerIdentity identity = mapper.findByPersonName(normalize(personName));
        if (identity == null || identity.getId() == null) {
            return;
        }
        if (!speakerServiceIntegration.isEnabled()) {
            return;
        }
        // 不再因"已注册"跳过：后续会议再绑同名的人时追加样本（speaker-service 端有上限保护），
        // 让声纹随会议累积、越来越准，避免"首次注册后再也认不出"。会话内重复由各入口自身去重。
        List<byte[]> chunks = splitPcmIntoChunks(fullPcm,
                Constants.SPEAKER_VOICE_ENROLL_CHUNK_SECONDS, Constants.SPEAKER_VOICE_ENROLL_MAX_CHUNKS);
        String name = identity.getPersonName();
        int enrolled = 0;
        int totalCount = 0;
        for (byte[] chunk : chunks) {
            if (chunk == null || chunk.length == 0) continue;
            int count = speakerServiceIntegration.enroll(name, chunk, true);
            if (count >= 0) { enrolled++; totalCount = count; }
        }
        if (enrolled > 0 && (identity.getSpeakerProfileId() == null || identity.getSpeakerProfileId().isBlank())) {
            mapper.updateProfileId(identity.getId(), normalize(name));
        }
        log.info("[SpeakerIdentityService] enrollSamplesByPersonName done, personName={}, samplesAdded={}, totalEmbeddings={}",
                name, enrolled, totalCount);
    }

    /** 把累计 PCM（16kHz mono 16-bit）切成多条 ~chunkSeconds 样本（尾部不足半条则并入上一条）。 */
    static List<byte[]> splitPcmIntoChunks(byte[] pcm, int chunkSeconds, int maxChunks) {
        int chunkBytes = Constants.DEFAULT_SAMPLE_RATE_ASR * (Constants.BITS_PER_SAMPLE / 8) * chunkSeconds;
        if (pcm.length <= chunkBytes) return List.of(pcm);
        List<byte[]> chunks = new java.util.ArrayList<>();
        int off = 0;
        while (off + chunkBytes <= pcm.length && chunks.size() < maxChunks) {
            chunks.add(java.util.Arrays.copyOfRange(pcm, off, off + chunkBytes));
            off += chunkBytes;
        }
        int remaining = pcm.length - off;
        if (remaining > 0) {
            if (remaining >= chunkBytes / 2 && chunks.size() < maxChunks) {
                chunks.add(java.util.Arrays.copyOfRange(pcm, off, pcm.length));
            } else if (!chunks.isEmpty()) {
                int lastIdx = chunks.size() - 1;
                byte[] last = chunks.get(lastIdx);
                byte[] merged = new byte[last.length + remaining];
                System.arraycopy(last, 0, merged, 0, last.length);
                System.arraycopy(pcm, off, merged, last.length, remaining);
                chunks.set(lastIdx, merged);
            }
        }
        return chunks;
    }

    public SpeakerIdentityVo toVo(SpeakerIdentity identity) {
        if (identity == null) {
            return null;
        }
        return SpeakerIdentityVo.builder()
                .id(identity.getId())
                .personName(identity.getPersonName())
                .speakerProfileId(identity.getSpeakerProfileId())
                .cartesiaVoiceId(identity.getCartesiaVoiceId())
                .language(identity.getLanguage())
                .note(identity.getNote())
                .createTime(identity.getCreateTime())
                .updateTime(identity.getUpdateTime())
                .build();
    }

    private SessionSpeakerIdentityVo toVo(SessionSpeakerIdentity identity) {
        return SessionSpeakerIdentityVo.builder()
                .sessionId(identity.getSessionId())
                .speakerId(identity.getSpeakerId())
                .personName(identity.getPersonName())
                .speakerProfileId(identity.getSpeakerProfileId())
                .cartesiaVoiceId(identity.getCartesiaVoiceId())
                .status(identity.getStatus())
                .source(identity.getSource())
                .build();
    }

    private SessionSpeakerIdentity unknownSessionIdentity(String sessionId, String speakerId) {
        return SessionSpeakerIdentity.builder()
                .sessionId(sessionId)
                .speakerId(speakerId)
                .status(STATUS_UNKNOWN)
                .source(SOURCE_UNKNOWN)
                .build();
    }

    private String buildKey(String sessionId, String speakerId) {
        return sessionId + ":" + speakerId;
    }

    private boolean hasSpeakerId(String speakerId) {
        return speakerId != null && !speakerId.isBlank();
    }

    private boolean isUnknownSpeakerId(String speakerId) {
        return speakerId != null && Constants.SPEAKER_ID_UNKNOWN.equalsIgnoreCase(speakerId.trim());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? null : normalized;
    }

    /** Last-accepted speaker name + cosine score for a stream, used for switch hysteresis. */
    private record ResolvedScore(String personName, double score) {}

    @Data
    @Builder
    private static class SessionSpeakerIdentity {
        private String sessionId;
        private String speakerId;
        private String personName;
        private String speakerProfileId;
        private String cartesiaVoiceId;
        private String status;
        private String source;

        private SpeakerResolution toResolution() {
            return SpeakerResolution.builder()
                    .sessionId(sessionId)
                    .speakerId(speakerId)
                    .personName(personName)
                    .speakerProfileId(speakerProfileId)
                    .cartesiaVoiceId(cartesiaVoiceId)
                    .status(status)
                    .source(source)
                    .build();
        }
    }

    @Data
    @Builder
    public static class SpeakerResolution {
        private String sessionId;
        private String speakerId;
        private String personName;
        private String speakerProfileId;
        private String cartesiaVoiceId;
        private String status;
        private String source;

        private static SpeakerResolution unknown(String sessionId, String speakerId) {
            return SpeakerResolution.builder()
                    .sessionId(sessionId)
                    .speakerId(speakerId)
                    .status(STATUS_UNKNOWN)
                    .source(SOURCE_UNKNOWN)
                    .build();
        }
    }
}
