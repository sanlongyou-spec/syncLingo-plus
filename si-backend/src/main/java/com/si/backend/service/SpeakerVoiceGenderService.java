package com.si.backend.service;

import com.si.backend.config.VoiceGenderProperties;
import com.si.backend.integration.VoiceGenderDetectionResult;
import com.si.backend.integration.VoiceGenderIntegration;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class SpeakerVoiceGenderService {

    private static final String KEY_SEPARATOR = "\0";

    private final VoiceGenderProperties properties;
    private final SpeakerGenderAudioBufferService audioBufferService;
    private final VoiceGenderIntegration voiceGenderIntegration;
    private final ThreadPoolExecutor detectionExecutor;
    private final ConcurrentHashMap<String, VoiceGender> genderCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> attemptCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAttemptMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<String, Boolean> inFlightKeys = ConcurrentHashMap.newKeySet();

    public SpeakerVoiceGenderService(
            VoiceGenderProperties properties,
            SpeakerGenderAudioBufferService audioBufferService,
            VoiceGenderIntegration voiceGenderIntegration) {
        this.properties = properties;
        this.audioBufferService = audioBufferService;
        this.voiceGenderIntegration = voiceGenderIntegration;
        this.detectionExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())),
                runnable -> {
                    Thread thread = new Thread(runnable, "voice-gender-detector");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        log.info("[SpeakerVoiceGenderService] enabled={}, minAudioSeconds={}, maxAudioSeconds={}, queueCapacity={}",
                properties.isEnabled(), properties.getMinAudioSeconds(),
                properties.getMaxAudioSeconds(), properties.getQueueCapacity());
    }

    public void appendAudio(String sessionId, byte[] pcmFrame) {
        if (!properties.isEnabled()) {
            log.debug("[SpeakerVoiceGenderService] appendAudio skipped, enabled=false, sessionId={}, frameBytes={}",
                    sessionId, pcmFrame != null ? pcmFrame.length : 0);
            return;
        }
        // 当前说话人性别已判定（或已达最大重试）→ 整段会话复用同一结果，
        // 后续音频帧直接丢弃，不再缓冲/快照/调度，避免每帧拷贝与 CPU 抢占。
        String activeSpeakerId = audioBufferService.activeSpeaker(sessionId);
        if (activeSpeakerId != null && isDetectionSettled(cacheKey(sessionId, activeSpeakerId))) {
            audioBufferService.dropSpeakerBuffer(sessionId, activeSpeakerId);
            return;
        }
        audioBufferService.append(sessionId, pcmFrame);
        audioBufferService.snapshotActiveIfReady(sessionId).ifPresent(this::scheduleDetectionIfNeeded);
    }

    /** 该说话人是否已无需再检测：已判出 MALE/FEMALE，或已达最大重试次数。 */
    private boolean isDetectionSettled(String key) {
        VoiceGender gender = genderCache.get(key);
        if (gender == VoiceGender.MALE || gender == VoiceGender.FEMALE) {
            return true;
        }
        AtomicInteger attempts = attemptCounts.get(key);
        return attempts != null && attempts.get() >= properties.getMaxRetries();
    }

    public void observeSpeaker(String sessionId, String speakerId) {
        if (!properties.isEnabled()) {
            log.debug("[SpeakerVoiceGenderService] observeSpeaker skipped, enabled=false, sessionId={}, speakerId={}",
                    sessionId, speakerId);
            return;
        }
        log.debug("[SpeakerVoiceGenderService] observeSpeaker, sessionId={}, speakerId={}", sessionId, speakerId);
        List<SpeakerGenderAudioBufferService.SpeakerAudioSample> samples =
                audioBufferService.observeSpeaker(sessionId, speakerId);
        samples.forEach(this::scheduleDetectionIfNeeded);
    }

    public VoiceGender resolveGender(String sessionId, String speakerId) {
        if (!properties.isEnabled() || sessionId == null || speakerId == null || speakerId.isBlank()) {
            log.debug("[SpeakerVoiceGenderService] resolveGender skipped, enabled={}, sessionId={}, speakerId={}",
                    properties.isEnabled(), sessionId, speakerId);
            return VoiceGender.UNKNOWN;
        }
        VoiceGender gender = genderCache.getOrDefault(cacheKey(sessionId, speakerId), VoiceGender.UNKNOWN);
        log.info("[SpeakerVoiceGenderService] resolveGender, sessionId={}, speakerId={}, gender={}",
                sessionId, speakerId, gender);
        return gender;
    }

    public void cleanupSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String prefix = sessionId + KEY_SEPARATOR;
        genderCache.keySet().removeIf(key -> key.startsWith(prefix));
        attemptCounts.keySet().removeIf(key -> key.startsWith(prefix));
        lastAttemptMs.keySet().removeIf(key -> key.startsWith(prefix));
        inFlightKeys.removeIf(key -> key.startsWith(prefix));
        audioBufferService.cleanupSession(sessionId);
        log.debug("[SpeakerVoiceGenderService] cleanup sessionId={}", sessionId);
    }

    @PreDestroy
    public void shutdown() {
        detectionExecutor.shutdownNow();
    }

    private void scheduleDetectionIfNeeded(SpeakerGenderAudioBufferService.SpeakerAudioSample sample) {
        String key = cacheKey(sample.sessionId(), sample.speakerId());
        VoiceGender currentGender = genderCache.get(key);
        if (currentGender == VoiceGender.MALE || currentGender == VoiceGender.FEMALE) {
            log.info("[SpeakerVoiceGenderService] schedule skipped, sessionId={}, speakerId={}, reason=genderResolved, gender={}, durationMs={}",
                    sample.sessionId(), sample.speakerId(), currentGender, sample.durationMs());
            return;
        }
        AtomicInteger attempts = attemptCounts.computeIfAbsent(key, ignored -> new AtomicInteger(0));
        if (attempts.get() >= properties.getMaxRetries()) {
            log.info("[SpeakerVoiceGenderService] schedule skipped, sessionId={}, speakerId={}, reason=maxRetries, attempts={}, durationMs={}",
                    sample.sessionId(), sample.speakerId(), attempts.get(), sample.durationMs());
            return;
        }
        long nowMs = System.currentTimeMillis();
        Long lastAttempt = lastAttemptMs.get(key);
        if (lastAttempt != null && nowMs - lastAttempt < TimeUnit.SECONDS.toMillis(properties.getRetryIntervalSeconds())) {
            log.info("[SpeakerVoiceGenderService] schedule skipped, sessionId={}, speakerId={}, reason=retryInterval, attempts={}, waitMs={}, durationMs={}",
                    sample.sessionId(), sample.speakerId(), attempts.get(),
                    TimeUnit.SECONDS.toMillis(properties.getRetryIntervalSeconds()) - (nowMs - lastAttempt),
                    sample.durationMs());
            return;
        }
        if (!inFlightKeys.add(key)) {
            log.info("[SpeakerVoiceGenderService] schedule skipped, sessionId={}, speakerId={}, reason=inFlight, attempts={}, durationMs={}",
                    sample.sessionId(), sample.speakerId(), attempts.get(), sample.durationMs());
            return;
        }
        try {
            log.info("[SpeakerVoiceGenderService] schedule queued, sessionId={}, speakerId={}, durationMs={}, attempts={}, queueSize={}, remainingCapacity={}",
                    sample.sessionId(), sample.speakerId(), sample.durationMs(), attempts.get(),
                    detectionExecutor.getQueue().size(), detectionExecutor.getQueue().remainingCapacity());
            detectionExecutor.execute(() -> detect(sample, key));
        } catch (RejectedExecutionException e) {
            inFlightKeys.remove(key);
            log.warn("[SpeakerVoiceGenderService] detection queue full, skip sample, sessionId={}, speakerId={}, durationMs={}",
                    sample.sessionId(), sample.speakerId(), sample.durationMs());
        }
    }

    private void detect(SpeakerGenderAudioBufferService.SpeakerAudioSample sample, String key) {
        lastAttemptMs.put(key, System.currentTimeMillis());
        int attempt = attemptCounts.computeIfAbsent(key, ignored -> new AtomicInteger(0)).incrementAndGet();
        try {
            log.info("[SpeakerVoiceGenderService] detect start, sessionId={}, speakerId={}, durationMs={}, audioBytes={}, attempt={}, queueSize={}",
                    sample.sessionId(), sample.speakerId(), sample.durationMs(), sample.pcmData().length,
                    attempt, detectionExecutor.getQueue().size());
            VoiceGenderDetectionResult result = voiceGenderIntegration.detect(sample.pcmData(), sample.speakerId());
            VoiceGender acceptedGender = result.acceptedGender(
                    properties.getMinConfidence(), properties.getMinMargin());
            String acceptanceReason = result.acceptanceReason(
                    properties.getMinConfidence(), properties.getMinMargin());
            genderCache.put(key, acceptedGender);
            log.info("[SpeakerVoiceGenderService] detect end, sessionId={}, speakerId={}, accepted={}, reason={}, serviceReason={}, rawGender={}, confidence={}, minConfidence={}, minMargin={}, male={}, female={}, child={}, modelAvailable={}",
                    sample.sessionId(), sample.speakerId(), acceptedGender, acceptanceReason, result.serviceReason(),
                    result.gender(), result.confidence(), properties.getMinConfidence(), properties.getMinMargin(),
                    result.maleScore(), result.femaleScore(), result.childScore(), result.modelAvailable());
        } catch (Exception e) {
            genderCache.put(key, VoiceGender.UNKNOWN);
            log.warn("[SpeakerVoiceGenderService] detect failed, sessionId={}, speakerId={}, attempt={}, error={}",
                    sample.sessionId(), sample.speakerId(), attempt, e.getMessage());
        } finally {
            inFlightKeys.remove(key);
        }
    }

    private static String cacheKey(String sessionId, String speakerId) {
        return sessionId + KEY_SEPARATOR + speakerId;
    }
}
