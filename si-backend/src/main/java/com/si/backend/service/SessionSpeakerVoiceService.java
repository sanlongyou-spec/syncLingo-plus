package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.entity.SessionSpeakerVoice;
import com.si.backend.integration.CartesiaTtsIntegration;
import com.si.backend.mapper.SessionSpeakerVoiceMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话说话人自动音色克隆服务。
 * 依据 Azure diarization 的 Guest-n 标签归集音频，克隆成功后复用 Cartesia voiceId。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSpeakerVoiceService {

    private final SessionSpeakerVoiceMapper speakerVoiceMapper;
    private final CartesiaTtsIntegration cartesiaTtsIntegration;
    private final SpeakerIdentityService speakerIdentityService;

    private final Map<String, RollingAudioBuffer> sessionRecentAudioMap = new ConcurrentHashMap<>();
    private final Map<String, SpeakerAudioBuffer> speakerAudioMap = new ConcurrentHashMap<>();
    private final Set<String> cloningSpeakers = ConcurrentHashMap.newKeySet();
    private final Set<String> enrollingSpeakers = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void initTable() {
        log.info("[SessionSpeakerVoiceService] initTable start");
        speakerVoiceMapper.createTableIfNotExists();
        log.info("[SessionSpeakerVoiceService] initTable end");
    }

    public void startSession(String sessionId) {
        log.info("[SessionSpeakerVoiceService] startSession, sessionId={}", sessionId);
        sessionRecentAudioMap.put(sessionId, new RollingAudioBuffer(maxBytes(Constants.SPEAKER_VOICE_MAX_SAMPLE_SECONDS)));
    }

    public byte[] getRecentSessionPcm(String sessionId, int seconds) {
        RollingAudioBuffer recentAudio = sessionRecentAudioMap.get(sessionId);
        if (recentAudio == null) {
            return new byte[0];
        }
        return recentAudio.snapshotRecent(maxBytes(Math.max(1, seconds)));
    }

    public void appendSessionAudio(String sessionId, byte[] pcmFrame) {
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }
        sessionRecentAudioMap
                .computeIfAbsent(sessionId, key -> new RollingAudioBuffer(maxBytes(Constants.SPEAKER_VOICE_MAX_SAMPLE_SECONDS)))
                .append(pcmFrame);
    }

    public void collectAndCloneIfNeeded(String sessionId, String speakerId, String sourceLang) {
        if (!isValidSpeakerId(speakerId)) {
            return;
        }
        String key = buildSpeakerKey(sessionId, speakerId);
        SessionSpeakerVoice existing = ensureSpeakerVoice(sessionId, speakerId, sourceLang);
        boolean cloneDone = Constants.SPEAKER_VOICE_STATUS_READY.equals(existing.getCloneStatus())
                || Constants.SPEAKER_VOICE_STATUS_CLONING.equals(existing.getCloneStatus())
                || Constants.SPEAKER_VOICE_STATUS_FAILED.equals(existing.getCloneStatus());

        RollingAudioBuffer recentAudio = sessionRecentAudioMap.get(sessionId);
        if (recentAudio == null) {
            return;
        }
        byte[] recentPcm = recentAudio.snapshotRecent(recentBytes());
        if (recentPcm.length == 0) {
            return;
        }

        SpeakerAudioBuffer speakerAudio = speakerAudioMap
                .computeIfAbsent(key, ignored -> new SpeakerAudioBuffer(maxBytes(Constants.SPEAKER_VOICE_MAX_SAMPLE_SECONDS)));
        speakerAudio.append(recentPcm);
        int audioSeconds = bytesToSeconds(speakerAudio.size());

        if (!cloneDone) {
            updateCollectingStatus(sessionId, speakerId, sourceLang, audioSeconds);
        }

        log.debug("[SessionSpeakerVoiceService] speaker audio collected, sessionId={}, speakerId={}, audioSeconds={}, cloneDone={}",
                sessionId, speakerId, audioSeconds, cloneDone);

        // Capture personName at this moment (may be null if speaker not yet mapped)
        String personName = speakerIdentityService.getCachedSpeakerName(sessionId, speakerId);

        if (!cloneDone && audioSeconds >= Constants.SPEAKER_VOICE_TARGET_SAMPLE_SECONDS) {
            log.info("[SessionSpeakerVoiceService] triggering clone, sessionId={}, speakerId={}, audioSeconds={}", sessionId, speakerId, audioSeconds);
            triggerClone(sessionId, speakerId, sourceLang, speakerAudio.snapshot(), personName);
        }
        // Auto-enroll in speaker recognition service once enough audio is accumulated and speaker is mapped.
        // Continues to run even after Cartesia clone is done so late-mapped speakers can still be enrolled.
        if (audioSeconds >= Constants.SPEAKER_VOICE_ENROLL_SAMPLE_SECONDS && personName != null) {
            triggerEnroll(sessionId, speakerId, sourceLang, speakerAudio.snapshot(), personName);
        }
    }

    public String findReadyVoiceId(String sessionId, String speakerId) {
        if (!isValidSpeakerId(speakerId)) {
            return null;
        }
        SessionSpeakerVoice speakerVoice = speakerVoiceMapper.findBySessionAndSpeaker(sessionId, speakerId);
        if (speakerVoice == null || !Constants.SPEAKER_VOICE_STATUS_READY.equals(speakerVoice.getCloneStatus())) {
            return null;
        }
        return speakerVoice.getCartesiaVoiceId();
    }

    public SessionSpeakerVoice findReadyVoice(String sessionId, String speakerId) {
        if (!isValidSpeakerId(speakerId)) {
            return null;
        }
        SessionSpeakerVoice speakerVoice = speakerVoiceMapper.findBySessionAndSpeaker(sessionId, speakerId);
        if (speakerVoice == null || !Constants.SPEAKER_VOICE_STATUS_READY.equals(speakerVoice.getCloneStatus())) {
            return null;
        }
        return speakerVoice;
    }

    public List<SessionSpeakerVoice> getSessionSpeakerVoices(String sessionId) {
        log.info("[SessionSpeakerVoiceService] getSessionSpeakerVoices start, sessionId={}", sessionId);
        List<SessionSpeakerVoice> speakerVoices = speakerVoiceMapper.findBySessionId(sessionId);
        log.info("[SessionSpeakerVoiceService] getSessionSpeakerVoices end, sessionId={}, count={}",
                sessionId, speakerVoices.size());
        return speakerVoices;
    }

    public byte[] getSpeakerAudioPcm(String sessionId, String speakerId) {
        if (!isValidSpeakerId(speakerId)) {
            return new byte[0];
        }
        SpeakerAudioBuffer buf = speakerAudioMap.get(buildSpeakerKey(sessionId, speakerId));
        return buf == null ? new byte[0] : buf.snapshot();
    }

    public void cleanupSession(String sessionId) {
        log.info("[SessionSpeakerVoiceService] cleanupSession, sessionId={}", sessionId);
        sessionRecentAudioMap.remove(sessionId);
        speakerAudioMap.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        cloningSpeakers.removeIf(key -> key.startsWith(sessionId + ":"));
        enrollingSpeakers.removeIf(key -> key.startsWith(sessionId + ":"));
    }

    @Transactional
    protected SessionSpeakerVoice ensureSpeakerVoice(String sessionId, String speakerId, String sourceLang) {
        SessionSpeakerVoice existing = speakerVoiceMapper.findBySessionAndSpeaker(sessionId, speakerId);
        if (existing != null) {
            return existing;
        }
        SessionSpeakerVoice speakerVoice = new SessionSpeakerVoice();
        speakerVoice.setSessionId(sessionId);
        speakerVoice.setSpeakerId(speakerId);
        speakerVoice.setCloneStatus(Constants.SPEAKER_VOICE_STATUS_COLLECTING);
        speakerVoice.setAudioSeconds(0);
        speakerVoice.setLanguage(normalizeCloneLang(sourceLang));
        speakerVoiceMapper.insert(speakerVoice);
        log.info("[SessionSpeakerVoiceService] speaker voice row created, sessionId={}, speakerId={}, id={}",
                sessionId, speakerId, speakerVoice.getId());
        return speakerVoice;
    }

    private void updateCollectingStatus(String sessionId, String speakerId, String sourceLang, int audioSeconds) {
        SessionSpeakerVoice speakerVoice = new SessionSpeakerVoice();
        speakerVoice.setSessionId(sessionId);
        speakerVoice.setSpeakerId(speakerId);
        speakerVoice.setCloneStatus(Constants.SPEAKER_VOICE_STATUS_COLLECTING);
        speakerVoice.setAudioSeconds(audioSeconds);
        speakerVoice.setLanguage(normalizeCloneLang(sourceLang));
        speakerVoiceMapper.updateStatus(speakerVoice);
    }

    private void triggerClone(String sessionId, String speakerId, String sourceLang, byte[] pcmSample, String personName) {
        String key = buildSpeakerKey(sessionId, speakerId);
        if (!cloningSpeakers.add(key)) {
            return;
        }

        SessionSpeakerVoice cloning = new SessionSpeakerVoice();
        cloning.setSessionId(sessionId);
        cloning.setSpeakerId(speakerId);
        cloning.setCloneStatus(Constants.SPEAKER_VOICE_STATUS_CLONING);
        cloning.setAudioSeconds(bytesToSeconds(pcmSample.length));
        cloning.setLanguage(normalizeCloneLang(sourceLang));
        speakerVoiceMapper.updateStatus(cloning);

        final String lang = normalizeCloneLang(sourceLang);
        CompletableFuture.runAsync(() -> {
            try {
                byte[] wavSample = wrapPcmAsWav(pcmSample);
                String voiceName = "session-" + sessionId.substring(0, Math.min(8, sessionId.length())) + "-" + speakerId;
                String voiceId = cartesiaTtsIntegration.createVoice(wavSample, voiceName, lang);
                SessionSpeakerVoice ready = new SessionSpeakerVoice();
                ready.setSessionId(sessionId);
                ready.setSpeakerId(speakerId);
                ready.setCartesiaVoiceId(voiceId);
                ready.setCloneStatus(Constants.SPEAKER_VOICE_STATUS_READY);
                ready.setAudioSeconds(bytesToSeconds(pcmSample.length));
                ready.setLanguage(lang);
                speakerVoiceMapper.updateStatus(ready);
                // If personName was known at trigger time, bind directly to SpeakerIdentity (avoids session-cleanup race).
                // Fallback to sessionIdentityMap lookup for the case where mapping happened during clone.
                if (personName != null && !personName.isBlank()) {
                    speakerIdentityService.bindCartesiaVoiceDirect(personName, voiceId, lang);
                } else {
                    speakerIdentityService.bindCartesiaVoiceFromSessionSpeaker(sessionId, speakerId, voiceId, lang);
                }
                log.info("[SessionSpeakerVoiceService] speaker voice clone ready, sessionId={}, speakerId={}, voiceId={}, personName={}",
                        sessionId, speakerId, voiceId, personName);
            } catch (Exception e) {
                SessionSpeakerVoice failed = new SessionSpeakerVoice();
                failed.setSessionId(sessionId);
                failed.setSpeakerId(speakerId);
                failed.setCloneStatus(Constants.SPEAKER_VOICE_STATUS_FAILED);
                failed.setAudioSeconds(bytesToSeconds(pcmSample.length));
                failed.setLanguage(lang);
                failed.setErrorMessage(e.getMessage());
                speakerVoiceMapper.updateStatus(failed);
                log.error("[SessionSpeakerVoiceService] speaker voice clone failed, sessionId={}, speakerId={}",
                        sessionId, speakerId, e);
            } finally {
                cloningSpeakers.remove(key);
            }
        });
    }

    private void triggerEnroll(String sessionId, String speakerId, String sourceLang, byte[] pcmSample, String personName) {
        String key = buildSpeakerKey(sessionId, speakerId);
        if (!enrollingSpeakers.add(key)) {
            return;
        }
        final String lang = normalizeCloneLang(sourceLang);
        CompletableFuture.runAsync(() -> {
            try {
                speakerIdentityService.enrollByPersonName(personName, pcmSample, lang);
                log.info("[SessionSpeakerVoiceService] auto-enrolled speaker from session audio, " +
                        "sessionId={}, speakerId={}, personName={}, audioSeconds={}",
                        sessionId, speakerId, personName, bytesToSeconds(pcmSample.length));
            } catch (Exception e) {
                log.warn("[SessionSpeakerVoiceService] auto-enroll failed, sessionId={}, speakerId={}, reason={}",
                        sessionId, speakerId, e.getMessage());
            }
            // Don't remove from enrollingSpeakers — prevents re-enrollment in the same session
        });
    }

    private boolean isValidSpeakerId(String speakerId) {
        return speakerId != null
                && !speakerId.isBlank()
                && !Constants.SPEAKER_ID_UNKNOWN.equalsIgnoreCase(speakerId);
    }

    private String buildSpeakerKey(String sessionId, String speakerId) {
        return sessionId + ":" + speakerId;
    }

    private String normalizeCloneLang(String sourceLang) {
        if (sourceLang == null || sourceLang.isBlank()) {
            return Constants.LANG_CLONE_ZH;
        }
        String lower = sourceLang.toLowerCase();
        if (lower.startsWith("id")) return Constants.LANG_CLONE_ID;
        if (lower.startsWith(Constants.LANG_EN_SHORT)) return Constants.LANG_CLONE_EN;
        return Constants.LANG_CLONE_ZH;
    }

    private int maxBytes(int seconds) {
        return Constants.DEFAULT_SAMPLE_RATE_ASR * Constants.AUDIO_CHANNELS_MONO
                * (Constants.BITS_PER_SAMPLE / 8) * seconds;
    }

    private int recentBytes() {
        return maxBytes(Constants.SPEAKER_VOICE_RECENT_AUDIO_SECONDS);
    }

    private int bytesToSeconds(int bytes) {
        return Math.max(0, bytes / maxBytes(1));
    }

    private byte[] wrapPcmAsWav(byte[] pcmSample) {
        int dataSize = pcmSample.length;
        int byteRate = Constants.DEFAULT_SAMPLE_RATE_ASR * Constants.AUDIO_CHANNELS_MONO * (Constants.BITS_PER_SAMPLE / 8);
        ByteArrayOutputStream wav = new ByteArrayOutputStream(Constants.WAV_HEADER_BYTES + dataSize);
        wav.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(intLe(Constants.WAV_HEADER_BYTES - 8 + dataSize));
        wav.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(intLe(16));
        wav.writeBytes(shortLe((short) 1));
        wav.writeBytes(shortLe((short) Constants.AUDIO_CHANNELS_MONO));
        wav.writeBytes(intLe(Constants.DEFAULT_SAMPLE_RATE_ASR));
        wav.writeBytes(intLe(byteRate));
        wav.writeBytes(shortLe((short) (Constants.AUDIO_CHANNELS_MONO * (Constants.BITS_PER_SAMPLE / 8))));
        wav.writeBytes(shortLe((short) Constants.BITS_PER_SAMPLE));
        wav.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(intLe(dataSize));
        wav.writeBytes(pcmSample);
        return wav.toByteArray();
    }

    private byte[] intLe(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private byte[] shortLe(short value) {
        return ByteBuffer.allocate(Short.BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array();
    }

    private static class RollingAudioBuffer {
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        RollingAudioBuffer(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized void append(byte[] bytes) {
            buffer.writeBytes(bytes);
            trimToMax();
        }

        synchronized byte[] snapshotRecent(int maxRecentBytes) {
            byte[] allBytes = buffer.toByteArray();
            int start = Math.max(0, allBytes.length - maxRecentBytes);
            return java.util.Arrays.copyOfRange(allBytes, start, allBytes.length);
        }

        private void trimToMax() {
            byte[] allBytes = buffer.toByteArray();
            if (allBytes.length <= maxBytes) {
                return;
            }
            byte[] trimmed = java.util.Arrays.copyOfRange(allBytes, allBytes.length - maxBytes, allBytes.length);
            buffer.reset();
            buffer.writeBytes(trimmed);
        }
    }

    private static class SpeakerAudioBuffer {
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        SpeakerAudioBuffer(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        synchronized void append(byte[] bytes) {
            if (buffer.size() >= maxBytes) {
                return;
            }
            int remaining = maxBytes - buffer.size();
            int bytesToWrite = Math.min(remaining, bytes.length);
            buffer.write(bytes, 0, bytesToWrite);
        }

        synchronized byte[] snapshot() {
            return buffer.toByteArray();
        }

        synchronized int size() {
            return buffer.size();
        }
    }
}
