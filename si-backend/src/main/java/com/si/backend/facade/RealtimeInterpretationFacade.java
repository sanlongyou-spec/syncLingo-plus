package com.si.backend.facade;

import com.si.backend.common.Constants;
import com.si.backend.config.CartesiaProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AsrService;
import com.si.backend.service.AudioRecordService;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.SessionSpeakerNameService;
import com.si.backend.service.SpeakerTurnService;
import com.si.backend.service.TtsService;
import com.si.backend.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeInterpretationFacade {

    private final AsrService asrService;
    private final TtsService ttsService;
    private final TranslationService translationService;
    private final InterpretationSessionService sessionService;
    private final CartesiaProperties cartesiaProperties;
    private final InterpretationRecordService recordService;
    private final SessionSpeakerNameService sessionSpeakerNameService;
    private final AudioRecordService audioRecordService;
    private final SpeakerTurnService speakerTurnService;

    private static final int TRANSLATION_THREAD_MULTIPLIER = 2;
    private static final int TTS_THREAD_MULTIPLIER = 2;
    private static final int TTS_QUEUE_LIMIT = 6;
    private static final long COMPRESS_MIN_SPEAKING_MS = 1500L;

    private static final Executor TRANSLATION_EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * TRANSLATION_THREAD_MULTIPLIER,
            Runtime.getRuntime().availableProcessors() * TRANSLATION_THREAD_MULTIPLIER,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private static final Executor TTS_EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * TTS_THREAD_MULTIPLIER,
            Runtime.getRuntime().availableProcessors() * TTS_THREAD_MULTIPLIER,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final ConcurrentHashMap<String, TranslationResultCallback> sessionTranslatedCallbackMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TtsAudioCallback> sessionTtsAudioCallbackMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AsrErrorCallback> sessionErrorCallbackMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionTtsChain = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionFinalRecognitionChain = new ConcurrentHashMap<>();
    private static final TtsBufferedChunk TTS_END = new TtsBufferedChunk(null, null, null, -1L, -1, -1L);
    private final ConcurrentHashMap<String, String> sessionLastSourceLang = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionTargetLangMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sessionTtsQueueSize = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sessionTtsSequence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sessionUtteranceStartMs = new ConcurrentHashMap<>();

    // ---------- WebSocket session lifecycle ----------

    public void startInterpretation(
            String sessionId, String sourceLang, String targetLang, String voiceId,
            AsrRecognitionCallback onRecognizing, AsrRecognitionCallback onRecognized,
            TranslationResultCallback onTranslated, TtsAudioCallback onTtsAudio, AsrErrorCallback onError) {
        log.info("[RealtimeInterpretationFacade] startInterpretation, sessionId={}, sourceLang={}, targetLang={}, voiceId={}",
                sessionId, sourceLang, targetLang, voiceId);
        sessionTtsChain.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + "::"));
        sessionFinalRecognitionChain.remove(sessionId);
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.put(sessionId, normalizeTargetLang(targetLang));
        sessionTtsQueueSize.computeIfAbsent(sessionId, key -> new AtomicLong(0)).set(0);
        sessionTtsSequence.computeIfAbsent(sessionId, key -> new AtomicLong(0)).set(0);
        sessionUtteranceStartMs.computeIfAbsent(sessionId, key -> new AtomicLong(0)).set(0);
        Long audioUserId = sessionService.getSession(sessionId).map(InterpretationSession::getUserId).orElse(null);
        Long audioMeetingId = sessionService.getSession(sessionId).map(InterpretationSession::getMeetingId).orElse(null);
        audioRecordService.startRecording(sessionId, audioUserId, audioMeetingId);
        sessionTranslatedCallbackMap.put(sessionId, onTranslated);
        sessionTtsAudioCallbackMap.put(sessionId, onTtsAudio);
        sessionErrorCallbackMap.put(sessionId, onError);
        asrService.startRecognition(
                sessionId,
                sessionService.getSession(sessionId).map(InterpretationSession::getUserId).orElse(1L),
                sourceLang,
                sessionService.getSession(sessionId).map(InterpretationSession::getHotwordIds).orElse(null),
                sessionService.getSession(sessionId).map(InterpretationSession::getEnabledLanguages).orElse(null),
                (text, lang, speakerId) -> {
                    sessionUtteranceStartMs.computeIfAbsent(sessionId, k -> new AtomicLong(0))
                            .compareAndSet(0, System.currentTimeMillis());
                    onRecognizing.accept(text, lang, speakerId);
                },
                (text, lang, speakerId) -> {
                    onRecognized.accept(text, lang, speakerId);
                    long startedMs = sessionUtteranceStartMs.computeIfAbsent(sessionId, k -> new AtomicLong(0)).getAndSet(0);
                    long speechStartAtMs = startedMs > 0 ? startedMs : System.currentTimeMillis();
                    enqueueFinalRecognition(text, lang, speakerId, sessionId, voiceId, speechStartAtMs);
                },
                errorMessage -> onError.accept(errorMessage));
        log.info("[RealtimeInterpretationFacade] startInterpretation done, sessionId={}", sessionId);
    }

    public void pushAudio(String sessionId, byte[] pcmFrame) {
        if (!sessionService.isSessionActive(sessionId)) return;
        asrService.pushAudio(sessionId, pcmFrame);
        if (pcmFrame != null && pcmFrame.length > 0) {
            audioRecordService.appendPcm(sessionId, pcmFrame);
            long audioMs = Math.round((double) pcmFrame.length * 1000
                    / (Constants.DEFAULT_SAMPLE_RATE_ASR * Constants.AUDIO_CHANNELS_MONO * (Constants.BITS_PER_SAMPLE / 8)));
            sessionService.addAsrAudioMs(sessionId, audioMs);
        }
    }

    public void stopInterpretation(String sessionId) {
        log.info("[RealtimeInterpretationFacade] stopInterpretation, sessionId={}", sessionId);
        try { asrService.stopRecognition(sessionId); }
        catch (Exception e) { log.error("[RealtimeInterpretationFacade] stopRecognition failed, sessionId={}", sessionId, e); }
        try { sessionService.stopSession(sessionId); }
        catch (Exception e) { log.error("[RealtimeInterpretationFacade] stopSession failed, sessionId={}", sessionId, e); }
        sessionTranslatedCallbackMap.remove(sessionId);
        sessionTtsAudioCallbackMap.remove(sessionId);
        sessionErrorCallbackMap.remove(sessionId);
        sessionTtsChain.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + "::"));
        sessionFinalRecognitionChain.remove(sessionId);
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.remove(sessionId);
        sessionTtsQueueSize.remove(sessionId);
        sessionTtsSequence.remove(sessionId);
        sessionUtteranceStartMs.remove(sessionId);
        audioRecordService.finalizeRecording(sessionId);
        recordService.cleanupSession(sessionId);
        speakerTurnService.flushSession(sessionId);
        speakerTurnService.cleanupSession(sessionId);
        sessionSpeakerNameService.cleanupSession(sessionId);
        log.info("[RealtimeInterpretationFacade] stopInterpretation done, sessionId={}", sessionId);
    }

    // ---------- Recognition -> Translation -> TTS pipeline ----------

    private void enqueueFinalRecognition(
            String text, String detectedLang, String speakerId,
            String sessionId, String voiceId, long speechStartAtMs) {
        sessionFinalRecognitionChain.compute(sessionId, (key, previous) -> {
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((ignored, error) -> null);
            return ready.thenRunAsync(
                    () -> processFinalRecognition(text, detectedLang, speakerId, sessionId, voiceId, speechStartAtMs),
                    TRANSLATION_EXECUTOR
            ).exceptionally(ex -> {
                log.error("[RealtimeInterpretationFacade] processFinalRecognition async error, sessionId={}", sessionId, ex);
                AsrErrorCallback errCb = sessionErrorCallbackMap.get(sessionId);
                if (errCb != null) errCb.accept("Translation processing failed: " + ex.getMessage());
                return null;
            });
        });
    }

    public void processFinalRecognition(String text, String detectedLang, String speakerId, String sessionId, String voiceId) {
        processFinalRecognition(text, detectedLang, speakerId, sessionId, voiceId, System.currentTimeMillis());
    }

    public void processFinalRecognition(String text, String detectedLang, String speakerId,
            String sessionId, String voiceId, long speechStartAtMs) {
        if (text == null || text.isBlank()) return;
        if (!isPipelineActive(sessionId, "process_final_start")) return;

        String speakerName = sessionSpeakerNameService.getName(sessionId, speakerId);
        log.debug("[RealtimeInterpretationFacade] speaker lookup sessionId={} speakerId={} speakerName={}",
                sessionId, speakerId, speakerName != null ? speakerName : "not mapped");

        // 根据 speakerId 跟踪说话人切换，切换确认后触发异步发言摘要
        speakerTurnService.processRecognized(sessionId, speakerId, text);

        String sourceLang = normalizeAsrLang(detectedLang);
        List<String> targetLangs = resolveTargetLangs(sessionId, sourceLang);
        log.info("[RealtimeInterpretationFacade] processFinalRecognition, sessionId={}, speakerId={}, speakerName={}, detected={}, sourceLang={}, targetLangs={}",
                sessionId, speakerId, speakerName, detectedLang, sourceLang, targetLangs);

        String prevLang = sessionLastSourceLang.put(sessionId, sourceLang);
        if (prevLang != null && !prevLang.equals(sourceLang)) {
            log.info("[RealtimeInterpretationFacade] lang switch {}->{}, sessionId={}", prevLang, sourceLang, sessionId);
        }

        String resolvedVoiceId = voiceId;
        if (resolvedVoiceId == null || resolvedVoiceId.isBlank()) {
            resolvedVoiceId = sessionService.getSession(sessionId).map(InterpretationSession::getVoiceId).orElse(null);
        }
        final String finalVoiceId = resolvedVoiceId;
        final String finalSpeakerName = speakerName;

        targetLangs.forEach(targetLang -> {
            if (!isPipelineActive(sessionId, "before_target_translate")) return;
            TtsPlaybackReservation reservation = reserveTtsPlayback(sessionId, targetLang);
            CompletableFuture.runAsync(
                    () -> translateAndStreamTts(text, sourceLang, targetLang, finalVoiceId,
                            sessionId, speakerId, finalSpeakerName, speechStartAtMs, reservation),
                    TRANSLATION_EXECUTOR
            ).exceptionally(ex -> {
                completeTtsReservation(reservation, "parallel_translate_error");
                log.error("[RealtimeInterpretationFacade] parallel translate error, sessionId={}, targetLang={}", sessionId, targetLang, ex);
                return null;
            });
        });
        log.info("[RealtimeInterpretationFacade] processFinalRecognition done, sessionId={}", sessionId);
    }

    private String normalizeAsrLang(String asrLang) {
        if (asrLang == null) return Constants.LANG_ZH_CN;
        String lower = asrLang.toLowerCase();
        if (lower.startsWith("zh") || lower.startsWith("zh-hans")) return Constants.LANG_ZH_CN;
        if (lower.startsWith("id")) return Constants.LANG_ID_SHORT;
        if (lower.startsWith(Constants.LANG_EN_SHORT)) return Constants.LANG_EN_SHORT;
        return Constants.LANG_ZH_CN;
    }

    private List<String> resolveTargetLangs(String sessionId, String sourceLang) {
        String requestedTargetLang = sessionTargetLangMap.getOrDefault(sessionId, Constants.LANG_AUTO);
        if (!isAutoTargetLang(requestedTargetLang)) {
            if (isSameLanguage(sourceLang, requestedTargetLang)) {
                log.info("[RealtimeInterpretationFacade] fixed target equals source, skip, sessionId={}, lang={}", sessionId, sourceLang);
                return List.of();
            }
            return List.of(requestedTargetLang);
        }
        List<String> enabledLanguages = sessionService.getSession(sessionId)
                .map(InterpretationSession::getEnabledLanguages)
                .map(this::parseEnabledLanguages)
                .orElse(List.of(Constants.LANG_ZH_CN, Constants.LANG_ID_SHORT));
        return enabledLanguages.stream()
                .map(this::normalizeAsrLang)
                .filter(lang -> !isSameLanguage(sourceLang, lang))
                .distinct()
                .toList();
    }

    private String normalizeTargetLang(String targetLang) {
        if (targetLang == null || targetLang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(targetLang)) return Constants.LANG_AUTO;
        return normalizeAsrLang(targetLang);
    }

    private boolean isAutoTargetLang(String targetLang) {
        return targetLang == null || targetLang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(targetLang);
    }

    private boolean isSameLanguage(String sourceLang, String targetLang) {
        return normalizeAsrLang(sourceLang).equalsIgnoreCase(normalizeTargetLang(targetLang));
    }

    private List<String> parseEnabledLanguages(String enabledLanguages) {
        if (enabledLanguages == null || enabledLanguages.isBlank()) {
            return List.of(Constants.LANG_ZH_CN, Constants.LANG_ID_SHORT);
        }
        return List.of(enabledLanguages.split(","));
    }

    // ---------- Translation + TTS ----------

    public void translateAndStreamTts(
            String text, String sourceLang, String targetLang, String voiceId,
            String sessionId, String speakerId, String speakerName) {
        translateAndStreamTts(text, sourceLang, targetLang, voiceId, sessionId, speakerId, speakerName, System.currentTimeMillis());
    }

    public void translateAndStreamTts(
            String text, String sourceLang, String targetLang, String voiceId,
            String sessionId, String speakerId, String speakerName, long speechStartAtMs) {
        if (text == null || text.isBlank()) return;
        TtsPlaybackReservation reservation = reserveTtsPlayback(sessionId, targetLang);
        try {
            translateAndStreamTts(text, sourceLang, targetLang, voiceId, sessionId, speakerId, speakerName, speechStartAtMs, reservation);
        } catch (RuntimeException e) {
            completeTtsReservation(reservation, "translate_pipeline_exception");
            throw e;
        }
    }

    private void translateAndStreamTts(
            String text, String sourceLang, String targetLang, String voiceId,
            String sessionId, String speakerId, String speakerName, long speechStartAtMs,
            TtsPlaybackReservation reservation) {
        if (text == null || text.isBlank()) {
            completeTtsReservation(reservation, "blank_text");
            return;
        }
        if (!isPipelineActive(sessionId, "translate_start")) {
            completeTtsReservation(reservation, "inactive_translate_start");
            return;
        }
        log.info("[RealtimeInterpretationFacade] translateAndStreamTts, sessionId={}, speakerId={}, textLen={}, {}->>{}, voiceId={}",
                sessionId, speakerId, text.length(), sourceLang, targetLang, voiceId);

        long translateStart = System.currentTimeMillis();
        boolean backlog = !reservation.previous().isDone();
        long speakingMs = translateStart - speechStartAtMs;
        boolean isCompressTarget = Constants.LANG_ID_SHORT.equalsIgnoreCase(targetLang)
                || Constants.LANG_ID.equalsIgnoreCase(targetLang)
                || Constants.LANG_EN_SHORT.equalsIgnoreCase(targetLang)
                || Constants.LANG_EN_US.equalsIgnoreCase(targetLang);
        boolean wantCompress = backlog || (isCompressTarget && speakingMs >= COMPRESS_MIN_SPEAKING_MS);
        AtomicLong qSize = sessionTtsQueueSize.get(sessionId);
        log.info("[RealtimeInterpretationFacade] compress-decision, sessionId={}, taskId={}, lang={}, backlog={}, speakingMs={}, wantCompress={}, queueSize={}",
                sessionId, reservation.taskId(), targetLang, backlog, speakingMs, wantCompress, qSize != null ? qSize.get() : 0);

        String translated;
        try {
            Long userId = sessionService.getSession(sessionId).map(InterpretationSession::getUserId).orElse(1L);
            translated = translationService.translate(text, sourceLang, targetLang, userId, wantCompress);
        } catch (Exception e) {
            log.error("[RealtimeInterpretationFacade] translate failed, sessionId={}", sessionId, e);
            completeTtsReservation(reservation, "translate_failed");
            return;
        }
        long translateDoneMs = System.currentTimeMillis();
        log.info("[RealtimeInterpretationFacade] translate done, sessionId={}, costMs={}, translatedLen={}",
                sessionId, translateDoneMs - translateStart, translated != null ? translated.length() : 0);

        if (translated == null || translated.isBlank()) {
            completeTtsReservation(reservation, "blank_translation");
            return;
        }
        if (!isPipelineActive(sessionId, "before_save_record")) {
            completeTtsReservation(reservation, "inactive_before_save");
            return;
        }
        sessionService.addTranslateChars(sessionId, text.length());
        sessionService.addTtsChars(sessionId, translated.length());
        if (isCompressionDirection(sourceLang, targetLang)) {
            sessionService.addLlmTokens(sessionId, estimateTokens(text), estimateTokens(translated));
        }
        recordService.saveTranslatedRecord(sessionId, sourceLang, targetLang, text, translated);

        TranslationResultCallback onTranslated = sessionTranslatedCallbackMap.get(sessionId);
        if (onTranslated != null && isPipelineActive(sessionId, "before_translated_callback")) {
            onTranslated.accept(text, translated, sourceLang, targetLang, speakerId, speakerName);
        }

        if (!isPipelineActive(sessionId, "before_tts_queue")) {
            completeTtsReservation(reservation, "inactive_before_tts");
            return;
        }
        final String resolvedVoiceId = resolveVoiceId(voiceId, targetLang);
        final String finalTranslated = translated;
        final String finalTargetLang = targetLang;
        final long ttsSequence = reservation.sequence();
        final String ttsTaskId = reservation.taskId();
        final double ttsSpeed = resolveTtsSpeed(targetLang, text.length(), translated.length());
        final int ttsSampleRate = cartesiaProperties.getTts().getSampleRate();

        log.info("[RealtimeInterpretationFacade] TTS queued, sessionId={}, taskId={}, sequence={}, textLen={}, voiceId={}, speed={}, prevDone={}",
                sessionId, ttsTaskId, ttsSequence, finalTranslated.length(), resolvedVoiceId, ttsSpeed, reservation.previous().isDone());

        BlockingQueue<TtsBufferedChunk> audioQueue = new LinkedBlockingQueue<>();
        final AtomicLong firstChunkGenMs = new AtomicLong(0);
        final AtomicLong totalPcmBytes = new AtomicLong(0);

        CompletableFuture.runAsync(() -> {
            if (!isPipelineActive(sessionId, "before_tts_synth")) {
                audioQueue.offer(TTS_END);
                return;
            }
            long ttsStart = System.currentTimeMillis();
            AtomicBoolean firstChunkLogged = new AtomicBoolean(false);
            AtomicLong chunkCounter = new AtomicLong(0);
            ttsService.synthesizeStream(
                    resolvedVoiceId, finalTranslated, cartesiaProperties.getTts().getSampleRate(),
                    ttsSpeed, cartesiaLanguage(finalTargetLang),
                    pcm -> {
                        if (!reservation.completion().isDone() && sessionService.isSessionActive(sessionId)) {
                            int chunkIndex = Math.toIntExact(chunkCounter.getAndIncrement());
                            totalPcmBytes.addAndGet(pcm.length);
                            if (firstChunkLogged.compareAndSet(false, true)) {
                                long firstChunkMs = System.currentTimeMillis();
                                firstChunkGenMs.set(firstChunkMs);
                                log.info("[RealtimeInterpretationFacade] TTS first chunk, sessionId={}, taskId={}, sequence={}, costMs={}, bytes={}",
                                        sessionId, ttsTaskId, ttsSequence, firstChunkMs - ttsStart, pcm.length);
                                log.info("[RealtimeInterpretationFacade] e2e speech-to-tts(server), sessionId={}, targetLang={}, captureToFirstAudioMs={}, textLen={}",
                                        sessionId, finalTargetLang, firstChunkMs - speechStartAtMs, finalTranslated.length());
                                log.info("[RealtimeInterpretationFacade] latency-breakdown, sessionId={}, taskId={}, sequence={}, lang={}, asrMs={}, translateMs={}, gapMs={}, ttsMs={}, totalMs={}, textLen={}",
                                        sessionId, ttsTaskId, ttsSequence, finalTargetLang,
                                        translateStart - speechStartAtMs,
                                        translateDoneMs - translateStart,
                                        ttsStart - translateDoneMs,
                                        firstChunkMs - ttsStart,
                                        firstChunkMs - speechStartAtMs,
                                        finalTranslated.length());
                            }
                            audioQueue.offer(new TtsBufferedChunk(pcm, finalTargetLang, ttsTaskId, ttsSequence, chunkIndex, System.nanoTime()));
                        }
                    },
                    () -> {
                        try {
                            log.info("[RealtimeInterpretationFacade] TTS synth complete, sessionId={}, taskId={}, sequence={}, chunks={}, costMs={}",
                                    sessionId, ttsTaskId, ttsSequence, chunkCounter.get(), System.currentTimeMillis() - ttsStart);
                            long audioDurationMs = ttsSampleRate > 0
                                    ? totalPcmBytes.get() * 1000L / ((long) ttsSampleRate * 2L) : -1L;
                            log.info("[RealtimeInterpretationFacade] tts-audio-duration, sessionId={}, taskId={}, lang={}, audioDurationMs={}, sourceSpeechWindowMs={}, textLen={}",
                                    sessionId, ttsTaskId, finalTargetLang, audioDurationMs,
                                    translateStart - speechStartAtMs, finalTranslated.length());
                        } finally {
                            audioQueue.offer(TTS_END);
                        }
                    },
                    err -> {
                        try {
                            log.error("[RealtimeInterpretationFacade] TTS synth error, sessionId={}, taskId={}, sequence={}, error={}",
                                    sessionId, ttsTaskId, ttsSequence, err);
                        } finally {
                            audioQueue.offer(TTS_END);
                        }
                    });
        }, TTS_EXECUTOR).exceptionally(ex -> {
            log.error("[RealtimeInterpretationFacade] TTS synth task error, sessionId={}", sessionId, ex);
            audioQueue.offer(TTS_END);
            return null;
        });

        reservation.previous().thenRunAsync(() -> {
            long playbackStart = System.currentTimeMillis();
            long lastChunkTimeNanos = -1L;
            long lastSendTimeNanos = -1L;
            boolean firstSentLogged = false;
            try {
                while (true) {
                    TtsBufferedChunk chunk = audioQueue.poll(Constants.TTS_STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (chunk == null) {
                        log.warn("[RealtimeInterpretationFacade] TTS playback timeout, sessionId={}", sessionId);
                        completeTtsReservation(reservation, "playback_timeout");
                        return;
                    }
                    if (chunk == TTS_END) {
                        log.info("[RealtimeInterpretationFacade] TTS stream complete, sessionId={}, costMs={}",
                                sessionId, System.currentTimeMillis() - playbackStart);
                        completeTtsReservation(reservation, "stream_complete");
                        return;
                    }
                    if (!isPipelineActive(sessionId, "before_tts_send")) {
                        completeTtsReservation(reservation, "inactive_before_send");
                        return;
                    }
                    if (lastChunkTimeNanos > 0) {
                        long chunkGapNanos = chunk.createdAtNanos() - lastChunkTimeNanos;
                        if (chunkGapNanos > 0) sleepUntil(lastSendTimeNanos + chunkGapNanos);
                    }
                    TtsAudioCallback onTtsAudio = sessionTtsAudioCallbackMap.get(sessionId);
                    if (onTtsAudio != null) {
                        onTtsAudio.accept(chunk.pcm(), chunk.lang(), chunk.taskId(), chunk.sequence(), chunk.chunkIndex(), speechStartAtMs);
                    }
                    if (!firstSentLogged) {
                        firstSentLogged = true;
                        long sentMs = System.currentTimeMillis();
                        long gen = firstChunkGenMs.get();
                        log.info("[RealtimeInterpretationFacade] tts-first-chunk-sent, sessionId={}, taskId={}, sequence={}, captureToSentMs={}, sendQueueWaitMs={}, speakMs={}, translateMs={}, ttsGenMs={}, compressed={}",
                                sessionId, chunk.taskId(), chunk.sequence(),
                                sentMs - speechStartAtMs,
                                gen > 0 ? sentMs - gen : -1L,
                                translateStart - speechStartAtMs,
                                translateDoneMs - translateStart,
                                gen > 0 ? gen - translateDoneMs : -1L,
                                wantCompress ? 1 : 0);
                    }
                    lastChunkTimeNanos = chunk.createdAtNanos();
                    lastSendTimeNanos = System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[RealtimeInterpretationFacade] TTS playback interrupted, sessionId={}", sessionId);
                completeTtsReservation(reservation, "playback_interrupted");
            } catch (Exception e) {
                log.error("[RealtimeInterpretationFacade] TTS playback error, sessionId={}", sessionId, e);
                completeTtsReservation(reservation, "playback_error");
            }
        }, TTS_EXECUTOR).exceptionally(ex -> {
            log.error("[RealtimeInterpretationFacade] TTS playback chain error, sessionId={}", sessionId, ex);
            completeTtsReservation(reservation, "playback_chain_error");
            return null;
        });
    }

    private TtsPlaybackReservation reserveTtsPlayback(String sessionId, String targetLang) {
        long sequence = sessionTtsSequence.computeIfAbsent(sessionId, key -> new AtomicLong(0)).incrementAndGet();
        String taskId = sessionId + "-" + sequence;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] previousHolder = new CompletableFuture[1];
        String chainKey = sessionId + "::" + targetLang;
        sessionTtsChain.compute(chainKey, (key, previous) -> {
            previousHolder[0] = previous != null ? previous : CompletableFuture.completedFuture(null);
            return completion;
        });
        long queueSize = sessionTtsQueueSize.computeIfAbsent(sessionId, key -> new AtomicLong(0)).incrementAndGet();
        if (queueSize > TTS_QUEUE_LIMIT) {
            log.warn("[RealtimeInterpretationFacade] TTS queue backlog high, sessionId={}, queueSize={}, warnLimit={}",
                    sessionId, queueSize, TTS_QUEUE_LIMIT);
        }
        log.info("[RealtimeInterpretationFacade] TTS order reserved, sessionId={}, lang={}, taskId={}, sequence={}, prevDone={}",
                sessionId, targetLang, taskId, sequence, previousHolder[0].isDone());
        return new TtsPlaybackReservation(sessionId, targetLang, taskId, sequence, previousHolder[0], completion, new AtomicBoolean(false));
    }

    private void completeTtsReservation(TtsPlaybackReservation reservation, String reason) {
        if (reservation.released().compareAndSet(false, true)) {
            reservation.completion().complete(null);
            decrementTtsQueue(reservation.sessionId());
            log.info("[RealtimeInterpretationFacade] TTS order released, sessionId={}, lang={}, taskId={}, sequence={}, reason={}",
                    reservation.sessionId(), reservation.lang(), reservation.taskId(), reservation.sequence(), reason);
        }
    }

    private void sleepUntil(long targetTimeNanos) throws InterruptedException {
        long remainingNanos = targetTimeNanos - System.nanoTime();
        if (remainingNanos > 0) TimeUnit.NANOSECONDS.sleep(remainingNanos);
    }

    private void decrementTtsQueue(String sessionId) {
        AtomicLong queueSize = sessionTtsQueueSize.get(sessionId);
        if (queueSize != null) {
            long currentSize = queueSize.updateAndGet(value -> Math.max(0, value - 1));
            log.debug("[RealtimeInterpretationFacade] TTS queue decremented, sessionId={}, queueSize={}", sessionId, currentSize);
        }
    }

    private boolean isPipelineActive(String sessionId, String stage) {
        boolean active = sessionService.isSessionActive(sessionId);
        if (!active) log.info("[RealtimeInterpretationFacade] skip inactive session pipeline, sessionId={}, stage={}", sessionId, stage);
        return active;
    }

    private boolean isCompressionDirection(String sourceLang, String targetLang) {
        return Constants.LANG_ZH_CN.equalsIgnoreCase(sourceLang) && Constants.LANG_ID_SHORT.equalsIgnoreCase(targetLang);
    }

    private long estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0L;
        return Math.max(1L, Math.round(text.length() / 2.0));
    }

    private record TtsBufferedChunk(
            byte[] pcm, String lang, String taskId, long sequence, int chunkIndex, long createdAtNanos) {}

    private record TtsPlaybackReservation(
            String sessionId, String lang, String taskId, long sequence,
            CompletableFuture<Void> previous, CompletableFuture<Void> completion, AtomicBoolean released) {}

    private String resolveVoiceId(String voiceId, String targetLang) {
        if (voiceId != null && !voiceId.isBlank()) return voiceId;
        if (Constants.LANG_ZH_CN.equalsIgnoreCase(targetLang) || Constants.LANG_CLONE_ZH.equalsIgnoreCase(targetLang)) {
            return cartesiaProperties.getDefaultVoiceIdChinese();
        }
        if (Constants.LANG_EN_SHORT.equalsIgnoreCase(targetLang)
                || Constants.LANG_EN_US.equalsIgnoreCase(targetLang)
                || Constants.LANG_CLONE_EN.equalsIgnoreCase(targetLang)) {
            String englishVoiceId = cartesiaProperties.getDefaultVoiceIdEnglish();
            if (englishVoiceId == null || englishVoiceId.isBlank()) {
                log.warn("[RealtimeInterpretationFacade] English voice is blank, fallback to default voice");
                return Constants.VOICE_ID_DEFAULT;
            }
            return englishVoiceId;
        }
        return cartesiaProperties.getDefaultVoiceIdIndonesian();
    }

    private String cartesiaLanguage(String targetLang) {
        if (targetLang == null) return null;
        String lower = targetLang.toLowerCase();
        if (lower.startsWith("id") || lower.startsWith("in")) return "id";
        if (lower.startsWith(Constants.LANG_EN_SHORT)) return "en";
        if (lower.startsWith("zh")) return "zh";
        return null;
    }

    private double resolveTtsSpeed(String targetLang, int sourceLen, int translatedLen) {
        if (targetLang == null) return Constants.TTS_SPEED_DEFAULT;
        String lower = targetLang.toLowerCase();
        boolean isId = lower.startsWith("id") || lower.startsWith("in");
        boolean isEn = lower.startsWith(Constants.LANG_EN_SHORT);
        if (!isId && !isEn) return Constants.TTS_SPEED_DEFAULT;

        double minSpeed = isId ? Constants.TTS_SPEED_INDONESIAN : Constants.TTS_SPEED_ENGLISH;
        if (sourceLen <= 0 || translatedLen <= 0) return minSpeed;

        // 动态语速：令 TTS 音频时长 ≈ 原声窗口时长。
        // Cartesia speed 参数与说话速率成正比，故 speed = translatedLen/sourceLen 时音频时长恰好等于原声。
        // 乘 0.9 留 10% 余量，确保 TTS 在下一句到达前播完；不低于语言最小速率。
        double targetSpeed = (double) translatedLen / sourceLen * 0.9;
        return Math.min(Math.max(targetSpeed, minSpeed), Constants.TTS_SPEED_MAX);
    }

    // ---------- Cleanup ----------

    public void cleanupSession(String sessionId) {
        log.info("[RealtimeInterpretationFacade] cleanupSession, sessionId={}", sessionId);
        try { asrService.stopRecognition(sessionId); }
        catch (Exception e) { log.error("[RealtimeInterpretationFacade] stopRecognition failed, sessionId={}", sessionId, e); }
        try {
            if (sessionService.isSessionActive(sessionId)) sessionService.stopSession(sessionId);
        } catch (Exception e) {
            log.error("[RealtimeInterpretationFacade] stopSession failed, sessionId={}", sessionId, e);
        }
        sessionTranslatedCallbackMap.remove(sessionId);
        sessionTtsAudioCallbackMap.remove(sessionId);
        sessionErrorCallbackMap.remove(sessionId);
        sessionTtsChain.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + "::"));
        sessionFinalRecognitionChain.remove(sessionId);
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.remove(sessionId);
        sessionTtsQueueSize.remove(sessionId);
        sessionTtsSequence.remove(sessionId);
        sessionUtteranceStartMs.remove(sessionId);
        audioRecordService.finalizeRecording(sessionId);
        recordService.cleanupSession(sessionId);
        speakerTurnService.flushSession(sessionId);
        speakerTurnService.cleanupSession(sessionId);
        sessionSpeakerNameService.cleanupSession(sessionId);
        log.info("[RealtimeInterpretationFacade] cleanupSession done, sessionId={}", sessionId);
    }

    public String getCachedSpeakerName(String sessionId, String speakerId) {
        return sessionSpeakerNameService.getName(sessionId, speakerId);
    }

    public String translateText(String text, String targetLang) {
        if (text == null || text.isBlank()) return "";
        log.info("[RealtimeInterpretationFacade] translateText, textLen={}, targetLang={}", text.length(), targetLang);
        try {
            String result = translationService.translate(text, Constants.LANG_AUTO, targetLang);
            log.info("[RealtimeInterpretationFacade] translateText done, resultLen={}", result != null ? result.length() : 0);
            return result != null ? result : "";
        } catch (Exception e) {
            log.error("[RealtimeInterpretationFacade] translateText failed, targetLang={}", targetLang, e);
            return "";
        }
    }

    @FunctionalInterface
    public interface AsrRecognitionCallback {
        void accept(String text, String language, String speakerId);
    }

    @FunctionalInterface
    public interface AsrErrorCallback {
        void accept(String errorMessage);
    }

    @FunctionalInterface
    public interface TranslationResultCallback {
        void accept(String originalText, String translatedText, String sourceLang, String targetLang, String speakerId, String speakerName);
    }

    @FunctionalInterface
    public interface TtsAudioCallback {
        void accept(byte[] pcmData, String targetLang, String ttsTaskId, long ttsSequence, int chunkIndex, long speechStartAtMs);
    }
}
