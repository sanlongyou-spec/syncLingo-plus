package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.config.VoiceGenderProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpeakerGenderAudioBufferService {

    private static final String KEY_SEPARATOR = "\0";

    private final VoiceGenderProperties properties;
    private final ConcurrentHashMap<String, String> activeSpeakerBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RollingPcmBuffer> speakerBuffers = new ConcurrentHashMap<>();

    public void append(String sessionId, byte[] pcmFrame) {
        if (!properties.isEnabled() || sessionId == null || pcmFrame == null || pcmFrame.length == 0) {
            log.debug("[SpeakerGenderAudioBuffer] append skipped, enabled={}, sessionId={}, frameBytes={}",
                    properties.isEnabled(), sessionId, pcmFrame != null ? pcmFrame.length : 0);
            return;
        }
        String speakerId = activeSpeakerBySession.get(sessionId);
        if (isUnknownSpeaker(speakerId)) {
            log.debug("[SpeakerGenderAudioBuffer] append skipped, sessionId={}, reason=noActiveSpeaker, frameBytes={}",
                    sessionId, pcmFrame.length);
            return;
        }
        RollingPcmBuffer buffer = bufferFor(sessionId, speakerId);
        buffer.append(pcmFrame);
        int bufferedBytes = buffer.size();
        log.debug("[SpeakerGenderAudioBuffer] append, sessionId={}, speakerId={}, frameBytes={}, bufferedBytes={}, bufferedMs={}, minBytes={}",
                sessionId, speakerId, pcmFrame.length, bufferedBytes, durationMs(bufferedBytes), minBytes());
    }

    public List<SpeakerAudioSample> observeSpeaker(String sessionId, String speakerId) {
        List<SpeakerAudioSample> samples = new ArrayList<>();
        if (!properties.isEnabled() || sessionId == null || isUnknownSpeaker(speakerId)) {
            log.debug("[SpeakerGenderAudioBuffer] observeSpeaker skipped, enabled={}, sessionId={}, speakerId={}",
                    properties.isEnabled(), sessionId, speakerId);
            return samples;
        }

        String previousSpeakerId = activeSpeakerBySession.put(sessionId, speakerId);
        if (previousSpeakerId == null) {
            log.info("[SpeakerGenderAudioBuffer] speaker observed, sessionId={}, speakerId={}", sessionId, speakerId);
        }
        if (previousSpeakerId != null && !previousSpeakerId.equals(speakerId)) {
            snapshotIfReady(sessionId, previousSpeakerId).ifPresent(samples::add);
            log.info("[SpeakerGenderAudioBuffer] speaker switch, sessionId={}, previous={}, current={}, readySamples={}",
                    sessionId, previousSpeakerId, speakerId, samples.size());
        }
        snapshotIfReady(sessionId, speakerId).ifPresent(samples::add);
        return samples;
    }

    /** 当前会话正在说话的 speakerId（无则返回 null），供调用方判断是否需要继续检测。 */
    public String activeSpeaker(String sessionId) {
        return sessionId == null ? null : activeSpeakerBySession.get(sessionId);
    }

    /** 释放某说话人的音频缓冲（性别已判定后不再需要继续缓冲）。 */
    public void dropSpeakerBuffer(String sessionId, String speakerId) {
        if (sessionId == null || speakerId == null) {
            return;
        }
        if (speakerBuffers.remove(bufferKey(sessionId, speakerId)) != null) {
            log.debug("[SpeakerGenderAudioBuffer] buffer dropped, sessionId={}, speakerId={}", sessionId, speakerId);
        }
    }

    public Optional<SpeakerAudioSample> snapshotActiveIfReady(String sessionId) {
        String speakerId = activeSpeakerBySession.get(sessionId);
        if (isUnknownSpeaker(speakerId)) {
            log.debug("[SpeakerGenderAudioBuffer] snapshotActive skipped, sessionId={}, reason=noActiveSpeaker", sessionId);
            return Optional.empty();
        }
        return snapshotIfReady(sessionId, speakerId);
    }

    public void cleanupSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        activeSpeakerBySession.remove(sessionId);
        String prefix = sessionId + KEY_SEPARATOR;
        speakerBuffers.keySet().removeIf(key -> key.startsWith(prefix));
        log.debug("[SpeakerGenderAudioBuffer] cleanup sessionId={}", sessionId);
    }

    private Optional<SpeakerAudioSample> snapshotIfReady(String sessionId, String speakerId) {
        RollingPcmBuffer buffer = speakerBuffers.get(bufferKey(sessionId, speakerId));
        if (buffer == null) {
            log.debug("[SpeakerGenderAudioBuffer] snapshot skipped, sessionId={}, speakerId={}, reason=noBuffer",
                    sessionId, speakerId);
            return Optional.empty();
        }
        int bufferedBytes = buffer.size();
        byte[] snapshot = buffer.snapshot(minBytes(), maxSampleBytes());
        if (snapshot.length < minBytes()) {
            log.debug("[SpeakerGenderAudioBuffer] snapshot not ready, sessionId={}, speakerId={}, bufferedBytes={}, bufferedMs={}, minBytes={}, minAudioSeconds={}",
                    sessionId, speakerId, bufferedBytes, durationMs(bufferedBytes), minBytes(), properties.getMinAudioSeconds());
            return Optional.empty();
        }
        log.info("[SpeakerGenderAudioBuffer] snapshot ready, sessionId={}, speakerId={}, bufferedBytes={}, sampleBytes={}, sampleMs={}, maxSampleBytes={}",
                sessionId, speakerId, bufferedBytes, snapshot.length, durationMs(snapshot.length), maxSampleBytes());
        return Optional.of(new SpeakerAudioSample(sessionId, speakerId, snapshot, durationMs(snapshot.length)));
    }

    private RollingPcmBuffer bufferFor(String sessionId, String speakerId) {
        return speakerBuffers.computeIfAbsent(bufferKey(sessionId, speakerId),
                ignored -> new RollingPcmBuffer(maxBufferBytes()));
    }

    private int bytesPerSecond() {
        return properties.getSampleRate()
                * Constants.AUDIO_CHANNELS_MONO
                * (Constants.BITS_PER_SAMPLE / 8);
    }

    private int minBytes() {
        return Math.max(bytesPerSecond(), properties.getMinAudioSeconds() * bytesPerSecond());
    }

    private int maxSampleBytes() {
        return Math.max(minBytes(), properties.getMaxAudioSeconds() * bytesPerSecond());
    }

    private int maxBufferBytes() {
        return Math.max(maxSampleBytes(), properties.getMaxBufferSeconds() * bytesPerSecond());
    }

    private long durationMs(int byteCount) {
        return Math.round((double) byteCount * 1000D / bytesPerSecond());
    }

    private static String bufferKey(String sessionId, String speakerId) {
        return sessionId + KEY_SEPARATOR + speakerId;
    }

    private static boolean isUnknownSpeaker(String speakerId) {
        return speakerId == null
                || speakerId.isBlank()
                || Constants.SPEAKER_ID_UNKNOWN.equalsIgnoreCase(speakerId.trim());
    }

    public record SpeakerAudioSample(String sessionId, String speakerId, byte[] pcmData, long durationMs) {}

    private static final class RollingPcmBuffer {
        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private RollingPcmBuffer(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        private synchronized void append(byte[] pcmFrame) {
            output.write(pcmFrame, 0, pcmFrame.length);
            trimToMaxBytes();
        }

        private synchronized int size() {
            return output.size();
        }

        private synchronized byte[] snapshot(int minBytes, int maxSampleBytes) {
            byte[] all = output.toByteArray();
            if (all.length < minBytes) {
                return new byte[0];
            }
            int takeBytes = Math.min(all.length, maxSampleBytes);
            byte[] sample = new byte[takeBytes];
            System.arraycopy(all, all.length - takeBytes, sample, 0, takeBytes);
            return sample;
        }

        private void trimToMaxBytes() {
            if (output.size() <= maxBytes) {
                return;
            }
            byte[] all = output.toByteArray();
            output.reset();
            output.write(all, all.length - maxBytes, maxBytes);
        }
    }
}
