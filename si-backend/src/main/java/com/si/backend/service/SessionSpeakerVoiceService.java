package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.entity.SessionSpeakerVoice;
import com.si.backend.mapper.SessionSpeakerVoiceMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话说话人 PCM 音频归集服务。
 * 依据 Azure diarization 的 Guest-n 标签归集音频，供声纹识别使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSpeakerVoiceService {

    private final SessionSpeakerVoiceMapper speakerVoiceMapper;

    private final Map<String, RollingAudioBuffer> sessionRecentAudioMap = new ConcurrentHashMap<>();
    private final Map<String, SpeakerAudioBuffer> speakerAudioMap = new ConcurrentHashMap<>();
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
        speakerAudioMap.forEach((key, buf) -> {
            if (key.startsWith(sessionId + ":")) {
                buf.append(pcmFrame);
            }
        });
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
        String key = buildSpeakerKey(sessionId, speakerId);
        SpeakerAudioBuffer buf = speakerAudioMap.computeIfAbsent(
                key, k -> new SpeakerAudioBuffer(maxBytes(Constants.SPEAKER_VOICE_MAX_SAMPLE_SECONDS)));
        return buf.snapshot();
    }

    public void cleanupSession(String sessionId) {
        log.info("[SessionSpeakerVoiceService] cleanupSession, sessionId={}", sessionId);
        sessionRecentAudioMap.remove(sessionId);
        speakerAudioMap.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        enrollingSpeakers.removeIf(key -> key.startsWith(sessionId + ":"));
    }

    private boolean isValidSpeakerId(String speakerId) {
        return speakerId != null
                && !speakerId.isBlank()
                && !Constants.SPEAKER_ID_UNKNOWN.equalsIgnoreCase(speakerId);
    }

    private String buildSpeakerKey(String sessionId, String speakerId) {
        return sessionId + ":" + speakerId;
    }

    private int maxBytes(int seconds) {
        return Constants.DEFAULT_SAMPLE_RATE_ASR * Constants.AUDIO_CHANNELS_MONO
                * (Constants.BITS_PER_SAMPLE / 8) * seconds;
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
    }
}
