package com.si.backend.facade;

import com.si.backend.common.Constants;
import com.si.backend.config.CartesiaProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AsrService;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.SessionSpeakerVoiceService;
import com.si.backend.service.SpeakerIdentityService;
import com.si.backend.service.TtsService;
import com.si.backend.service.TranslationService;
import com.si.backend.service.VoiceCloneService;
import com.si.backend.service.VoiceUsageRecordService;
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

/**
 * 实时同传门面层，协调 ASR、翻译、TTS 的实时处理流程。
 * 所有业务逻辑委托给 AsrService / TtsService，禁止直接调用 Integration 层。
 * 音频路由（VoiceMeeter 分发）完全由前端负责。
 */
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
    private final VoiceUsageRecordService voiceUsageRecordService;
    private final VoiceCloneService voiceCloneService;
    private final SessionSpeakerVoiceService sessionSpeakerVoiceService;
    private final SpeakerIdentityService speakerIdentityService;

    private static final int TRANSLATION_THREAD_MULTIPLIER = 2;
    private static final int TTS_THREAD_MULTIPLIER = 2;
    private static final int TTS_QUEUE_LIMIT = 6;

    /** 翻译任务专用线程池，有界队列防止 OOM，CallerRunsPolicy 提供背压 */
    private static final Executor TRANSLATION_EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * TRANSLATION_THREAD_MULTIPLIER,
            Runtime.getRuntime().availableProcessors() * TRANSLATION_THREAD_MULTIPLIER,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /** TTS 串行链专用线程池，与翻译线程池隔离，防止互相阻塞 */
    private static final Executor TTS_EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * TTS_THREAD_MULTIPLIER,
            Runtime.getRuntime().availableProcessors() * TTS_THREAD_MULTIPLIER,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /** 缓存每个会话的翻译结果回调，用于将译文推送给前端 */
    private final ConcurrentHashMap<String, TranslationResultCallback> sessionTranslatedCallbackMap = new ConcurrentHashMap<>();

    /** 缓存每个会话的 TTS 音频回调，用于将 PCM 数据推送给前端 */
    private final ConcurrentHashMap<String, TtsAudioCallback> sessionTtsAudioCallbackMap = new ConcurrentHashMap<>();

    /** 缓存每个会话的错误回调，用于将异步管道错误推送给前端 */
    private final ConcurrentHashMap<String, AsrErrorCallback> sessionErrorCallbackMap = new ConcurrentHashMap<>();

    /** Speaker identity update callbacks for the UI. */
    private final ConcurrentHashMap<String, SpeakerIdentityCallback> sessionSpeakerIdentityCallbackMap = new ConcurrentHashMap<>();

    /** 每个会话最后一个 TTS 播放任务的 Future；合成可重叠，音频下发保持串行 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionTtsChain = new ConcurrentHashMap<>();

    private static final TtsBufferedChunk TTS_END = new TtsBufferedChunk(null, null, null, -1L, -1, -1L);

    /** 每个会话上一次检测到的源语言，用于判断是否发生了语言切换 */
    private final ConcurrentHashMap<String, String> sessionLastSourceLang = new ConcurrentHashMap<>();

    /** Session-level requested target language; auto means translating to the other two languages. */
    private final ConcurrentHashMap<String, String> sessionTargetLangMap = new ConcurrentHashMap<>();

    /** 每个会话当前排队 TTS 数量，用于积压控制 */
    private final ConcurrentHashMap<String, AtomicLong> sessionTtsQueueSize = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sessionTtsSequence = new ConcurrentHashMap<>();

    // ---------- WebSocket session lifecycle ----------

    /**
     * 启动实时 ASR 识别与翻译。
     *
     * @param sessionId      WebSocket 会话 ID
     * @param sourceLang     源语言（传 "auto" 启用自动检测）
     * @param targetLang     目标语言
     * @param voiceId        音色 ID（可为空）
     * @param onRecognizing  识别回调（isFinal=false）
     * @param onRecognized   最终识别回调（isFinal=true）
     * @param onTranslated   翻译结果回调
     * @param onTtsAudio     TTS 音频块回调（将 PCM 推送给前端）
     * @param onError        错误回调
     */
    public void startInterpretation(
            String sessionId,
            String sourceLang,
            String targetLang,
            String voiceId,
            AsrRecognitionCallback onRecognizing,
            AsrRecognitionCallback onRecognized,
            TranslationResultCallback onTranslated,
            TtsAudioCallback onTtsAudio,
            SpeakerIdentityCallback onSpeakerIdentity,
            AsrErrorCallback onError
    ) {
        log.info("[RealtimeInterpretationFacade] startInterpretation, sessionId={}, sourceLang={}, targetLang={}, voiceId={}",
                sessionId, sourceLang, targetLang, voiceId);

        // 重置 TTS 链：防止 WS 重连时旧的未完成 Future 阻塞新任务
        sessionTtsChain.put(sessionId, CompletableFuture.completedFuture(null));
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.put(sessionId, normalizeTargetLang(targetLang));
        sessionTtsQueueSize.computeIfAbsent(sessionId, key -> new AtomicLong(0)).set(0);
        sessionTtsSequence.computeIfAbsent(sessionId, key -> new AtomicLong(0)).set(0);
        sessionSpeakerVoiceService.startSession(sessionId);
        speakerIdentityService.startSession(sessionId);

        sessionTranslatedCallbackMap.put(sessionId, onTranslated);
        sessionTtsAudioCallbackMap.put(sessionId, onTtsAudio);
        sessionSpeakerIdentityCallbackMap.put(sessionId, onSpeakerIdentity);
        sessionErrorCallbackMap.put(sessionId, onError);

        asrService.startRecognition(
                sessionId,
                sessionService.getSession(sessionId).map(InterpretationSession::getUserId).orElse(1L),
                sourceLang,
                sessionService.getSession(sessionId).map(InterpretationSession::getHotwordIds).orElse(null),
                sessionService.getSession(sessionId).map(InterpretationSession::getEnabledLanguages).orElse(null),
                (text, lang, speakerId) -> onRecognizing.accept(text, lang, speakerId),
                (text, lang, speakerId) -> {
                    onRecognized.accept(text, lang, speakerId);
                    CompletableFuture.runAsync(() ->
                            processFinalRecognition(text, lang, speakerId, sessionId, voiceId),
                            TRANSLATION_EXECUTOR)
                            .exceptionally(ex -> {
                                log.error("[RealtimeInterpretationFacade] processFinalRecognition async error, sessionId={}", sessionId, ex);
                                AsrErrorCallback errCb = sessionErrorCallbackMap.get(sessionId);
                                if (errCb != null) {
                                    errCb.accept("翻译处理失败: " + ex.getMessage());
                                }
                                return null;
                            });
                },
                errorMessage -> onError.accept(errorMessage)
        );
        log.info("[RealtimeInterpretationFacade] startInterpretation done, sessionId={}", sessionId);
    }

    /**
     * 推送音频帧到 ASR。
     *
     * @param sessionId WebSocket 会话 ID
     * @param pcmFrame  PCM 音频帧
     */
    public void pushAudio(String sessionId, byte[] pcmFrame) {
        if (!sessionService.isSessionActive(sessionId)) {
            return;
        }
        asrService.pushAudio(sessionId, pcmFrame);
        if (pcmFrame != null && pcmFrame.length > 0) {
            sessionSpeakerVoiceService.appendSessionAudio(sessionId, pcmFrame);
            long audioMs = Math.round((double) pcmFrame.length * 1000
                    / (Constants.DEFAULT_SAMPLE_RATE_ASR * Constants.AUDIO_CHANNELS_MONO * (Constants.BITS_PER_SAMPLE / 8)));
            sessionService.addAsrAudioMs(sessionId, audioMs);
        }
    }

    /**
     * 停止实时 ASR 识别。
     *
     * @param sessionId WebSocket 会话 ID
     */
    public void stopInterpretation(String sessionId) {
        log.info("[RealtimeInterpretationFacade] stopInterpretation, sessionId={}", sessionId);
        try {
            asrService.stopRecognition(sessionId);
        } catch (Exception e) {
            log.error("[RealtimeInterpretationFacade] stopRecognition failed, sessionId={}", sessionId, e);
        }
        try {
            sessionService.stopSession(sessionId);
        } catch (Exception e) {
            log.error("[RealtimeInterpretationFacade] stopSession failed, sessionId={}", sessionId, e);
        }
        sessionTranslatedCallbackMap.remove(sessionId);
        sessionTtsAudioCallbackMap.remove(sessionId);
        sessionErrorCallbackMap.remove(sessionId);
        sessionTtsChain.remove(sessionId);
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.remove(sessionId);
        sessionTtsQueueSize.remove(sessionId);
        sessionTtsSequence.remove(sessionId);
        recordService.cleanupSession(sessionId);
        sessionSpeakerVoiceService.cleanupSession(sessionId);
        speakerIdentityService.cleanupSession(sessionId);
        sessionSpeakerIdentityCallbackMap.remove(sessionId);
        log.info("[RealtimeInterpretationFacade] stopInterpretation done, sessionId={}", sessionId);
    }

    // ---------- Recognition → Translation → TTS pipeline ----------

    /**
     * 处理最终识别结果：根据检测到的语种自动选择翻译方向，然后 TTS 播报。
     */
    public void processFinalRecognition(String text, String detectedLang, String speakerId, String sessionId, String voiceId) {
        if (text == null || text.isBlank()) return;
        if (!isPipelineActive(sessionId, "process_final_start")) return;

        String sourceLang = normalizeAsrLang(detectedLang);
        List<String> targetLangs = resolveTargetLangs(sessionId, sourceLang);
        sessionSpeakerVoiceService.collectAndCloneIfNeeded(sessionId, speakerId, sourceLang);
        SpeakerIdentityService.SpeakerResolution speakerResolution = speakerIdentityService.resolveOrIdentify(
                sessionId,
                speakerId,
                sessionSpeakerVoiceService.getSpeakerAudioPcm(sessionId, speakerId)
        );
        notifySpeakerIdentity(sessionId, speakerResolution);

        log.info("[RealtimeInterpretationFacade] processFinalRecognition, sessionId={}, speakerId={}, speakerName={}, detected={}, sourceLang={}, targetLangs={}",
                sessionId, speakerId, speakerResolution.getPersonName(), detectedLang, sourceLang, targetLangs);

        // 检测语言切换时只记录，不重置 TTS 链，避免已排队音频被跳过。
        String prevLang = sessionLastSourceLang.put(sessionId, sourceLang);
        if (prevLang != null && !prevLang.equals(sourceLang)) {
            log.info("[RealtimeInterpretationFacade] lang switch {}->{}, keep queued TTS to avoid skip, sessionId={}",
                    prevLang, sourceLang, sessionId);
        }

        String resolvedVoiceId = voiceId;
        if (resolvedVoiceId == null || resolvedVoiceId.isBlank()) {
            resolvedVoiceId = sessionService.getSession(sessionId)
                    .map(InterpretationSession::getVoiceId)
                    .orElse(null);
        }

        for (String targetLang : targetLangs) {
            if (!isPipelineActive(sessionId, "before_target_translate")) {
                break;
            }
            translateAndStreamTts(text, sourceLang, targetLang, resolvedVoiceId, sessionId, speakerId, speakerResolution);
        }
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
                log.info("[RealtimeInterpretationFacade] fixed target equals source, skip translation, sessionId={}, lang={}",
                        sessionId, sourceLang);
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
        if (targetLang == null || targetLang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(targetLang)) {
            return Constants.LANG_AUTO;
        }
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

    /**
     * 翻译文本并流式合成 TTS 音频，通过回调推送给前端。
     */
    public void translateAndStreamTts(
            String text,
            String sourceLang,
            String targetLang,
            String voiceId,
            String sessionId,
            String speakerId,
            SpeakerIdentityService.SpeakerResolution speakerResolution
    ) {
        if (text == null || text.isBlank()) return;
        if (!isPipelineActive(sessionId, "translate_start")) return;

        log.info("[RealtimeInterpretationFacade] translateAndStreamTts, sessionId={}, speakerId={}, textLen={}, {}→{}, voiceId={}",
                sessionId, speakerId, text.length(), sourceLang, targetLang, voiceId);

        long translateStart = System.currentTimeMillis();
        String translated;
        try {
            Long userId = sessionService.getSession(sessionId)
                    .map(InterpretationSession::getUserId)
                    .orElse(1L);
            translated = translationService.translate(text, sourceLang, targetLang, userId);
        } catch (Exception e) {
            log.error("[RealtimeInterpretationFacade] translate failed, sessionId={}, {}→{}", sessionId, sourceLang, targetLang, e);
            return;
        }
        log.info("[RealtimeInterpretationFacade] translate done, sessionId={}, costMs={}, translatedLen={}",
                sessionId, System.currentTimeMillis() - translateStart, translated != null ? translated.length() : 0);

        if (translated == null || translated.isBlank()) return;
        if (!isPipelineActive(sessionId, "before_save_record")) return;
        sessionService.addTranslateChars(sessionId, text.length());
        sessionService.addTtsChars(sessionId, translated.length());
        if (isCompressionDirection(sourceLang, targetLang)) {
            sessionService.addLlmTokens(sessionId, estimateTokens(text), estimateTokens(translated));
        }
        recordService.saveTranslatedRecord(sessionId, sourceLang, targetLang, text, translated);

        TranslationResultCallback onTranslated = sessionTranslatedCallbackMap.get(sessionId);
        if (onTranslated != null && isPipelineActive(sessionId, "before_translated_callback")) {
            onTranslated.accept(text, translated, sourceLang, targetLang, speakerId, speakerResolution.getPersonName());
        }

        if (!isPipelineActive(sessionId, "before_tts_queue")) return;
        String identityVoiceId = speakerIdentityService.resolveVoiceId(sessionId, speakerId);
        String speakerVoiceId = identityVoiceId != null ? identityVoiceId : sessionSpeakerVoiceService.findReadyVoiceId(sessionId, speakerId);
        String authorizedVoiceId = speakerVoiceId != null ? speakerVoiceId : resolveVoiceId(voiceId, targetLang);
        if (speakerVoiceId != null) {
            log.info("[RealtimeInterpretationFacade] speaker voice resolved, sessionId={}, speakerId={}, voiceId={}",
                    sessionId, speakerId, speakerVoiceId);
        }
        if (!voiceCloneService.isVoiceUsable(authorizedVoiceId)) {
            log.warn("[RealtimeInterpretationFacade] voice disabled or unauthorized, fallback default, sessionId={}, voiceId={}",
                    sessionId, authorizedVoiceId);
            authorizedVoiceId = resolveVoiceId(null, targetLang);
        }
        final String resolvedVoiceId = authorizedVoiceId;
        final String finalTranslated = translated;
        final String finalTargetLang = targetLang;
        final long ttsSequence = sessionTtsSequence
                .computeIfAbsent(sessionId, key -> new AtomicLong(0))
                .incrementAndGet();
        final String ttsTaskId = sessionId + "-" + ttsSequence;

        CompletableFuture<Void> playFuture = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] prevHolder = new CompletableFuture[1];
        long queueSize = sessionTtsQueueSize
                .computeIfAbsent(sessionId, key -> new AtomicLong(0))
                .incrementAndGet();
        if (queueSize > TTS_QUEUE_LIMIT) {
            log.warn("[RealtimeInterpretationFacade] TTS queue backlog high, keep queue to avoid skip, sessionId={}, queueSize={}, warnLimit={}",
                    sessionId, queueSize, TTS_QUEUE_LIMIT);
        }
        sessionTtsChain.compute(sessionId, (k, prev) -> {
            prevHolder[0] = (prev != null) ? prev : CompletableFuture.completedFuture(null);
            return playFuture;
        });

        log.info("[RealtimeInterpretationFacade] TTS queued, sessionId={}, taskId={}, sequence={}, textLen={}, voiceId={}, prevDone={}",
                sessionId, ttsTaskId, ttsSequence, finalTranslated.length(), resolvedVoiceId, prevHolder[0].isDone());

        final double ttsSpeed = resolveTtsSpeed(targetLang);

        BlockingQueue<TtsBufferedChunk> audioQueue = new LinkedBlockingQueue<>();

        CompletableFuture.runAsync(() -> {
            if (!isPipelineActive(sessionId, "before_tts_synth")) {
                audioQueue.offer(TTS_END);
                return;
            }
            long ttsStart = System.currentTimeMillis();
            voiceUsageRecordService.recordUsage(
                    sessionId,
                    sessionService.getSession(sessionId).map(InterpretationSession::getUserId).orElse(null),
                    resolvedVoiceId,
                    finalTargetLang,
                    finalTranslated.length()
            );
            AtomicBoolean firstChunkLogged = new AtomicBoolean(false);
            AtomicLong chunkCounter = new AtomicLong(0);
            ttsService.synthesizeStream(
                    resolvedVoiceId,
                    finalTranslated,
                    cartesiaProperties.getTts().getSampleRate(),
                    ttsSpeed,
                    pcm -> {
                        if (!playFuture.isDone() && sessionService.isSessionActive(sessionId)) {
                            int chunkIndex = Math.toIntExact(chunkCounter.getAndIncrement());
                            if (firstChunkLogged.compareAndSet(false, true)) {
                                log.info("[RealtimeInterpretationFacade] TTS first chunk, sessionId={}, taskId={}, sequence={}, costMs={}, bytes={}",
                                        sessionId, ttsTaskId, ttsSequence, System.currentTimeMillis() - ttsStart, pcm.length);
                            }
                            audioQueue.offer(new TtsBufferedChunk(pcm, finalTargetLang, ttsTaskId, ttsSequence, chunkIndex, System.nanoTime()));
                        }
                    },
                    () -> {
                        try {
                            log.info("[RealtimeInterpretationFacade] TTS synth complete, sessionId={}, taskId={}, sequence={}, chunks={}, costMs={}",
                                    sessionId, ttsTaskId, ttsSequence, chunkCounter.get(), System.currentTimeMillis() - ttsStart);
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
                    }
            );
        }, TTS_EXECUTOR).exceptionally(ex -> {
            log.error("[RealtimeInterpretationFacade] TTS synth task error, sessionId={}", sessionId, ex);
            audioQueue.offer(TTS_END);
            return null;
        });

        prevHolder[0].thenRunAsync(() -> {
            long playbackStart = System.currentTimeMillis();
            long lastChunkTimeNanos = -1L;
            long lastSendTimeNanos = -1L;
            try {
                while (true) {
                    TtsBufferedChunk chunk = audioQueue.poll(Constants.TTS_STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (chunk == null) {
                        log.warn("[RealtimeInterpretationFacade] TTS playback timeout waiting chunk, sessionId={}, timeoutSeconds={}",
                                sessionId, Constants.TTS_STREAM_TIMEOUT_SECONDS);
                        playFuture.complete(null);
                        decrementTtsQueue(sessionId);
                        return;
                    }
                    if (chunk == TTS_END) {
                        log.info("[RealtimeInterpretationFacade] TTS stream complete, sessionId={}, costMs={}",
                                sessionId, System.currentTimeMillis() - playbackStart);
                        playFuture.complete(null);
                        decrementTtsQueue(sessionId);
                        return;
                    }

                    if (!isPipelineActive(sessionId, "before_tts_send")) {
                        playFuture.complete(null);
                        decrementTtsQueue(sessionId);
                        return;
                    }

                    if (lastChunkTimeNanos > 0) {
                        long chunkGapNanos = chunk.createdAtNanos() - lastChunkTimeNanos;
                        if (chunkGapNanos > 0) {
                            sleepUntil(lastSendTimeNanos + chunkGapNanos);
                        }
                    }

                    TtsAudioCallback onTtsAudio = sessionTtsAudioCallbackMap.get(sessionId);
                    if (onTtsAudio != null) {
                        onTtsAudio.accept(chunk.pcm(), chunk.lang(), chunk.taskId(), chunk.sequence(), chunk.chunkIndex());
                    }
                    lastChunkTimeNanos = chunk.createdAtNanos();
                    lastSendTimeNanos = System.nanoTime();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[RealtimeInterpretationFacade] TTS playback interrupted, sessionId={}", sessionId);
                playFuture.complete(null);
                decrementTtsQueue(sessionId);
            } catch (Exception e) {
                log.error("[RealtimeInterpretationFacade] TTS playback error, sessionId={}", sessionId, e);
                playFuture.complete(null);
                decrementTtsQueue(sessionId);
            }
        }, TTS_EXECUTOR).exceptionally(ex -> {
            log.error("[RealtimeInterpretationFacade] TTS playback chain error, sessionId={}", sessionId, ex);
            playFuture.complete(null);
            return null;
        });
    }

    private void sleepUntil(long targetTimeNanos) throws InterruptedException {
        long remainingNanos = targetTimeNanos - System.nanoTime();
        if (remainingNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(remainingNanos);
        }
    }

    private void decrementTtsQueue(String sessionId) {
        AtomicLong queueSize = sessionTtsQueueSize.get(sessionId);
        if (queueSize != null) {
            long currentSize = queueSize.updateAndGet(value -> Math.max(0, value - 1));
            log.debug("[RealtimeInterpretationFacade] TTS queue decremented, sessionId={}, queueSize={}",
                    sessionId, currentSize);
        }
    }

    private void notifySpeakerIdentity(String sessionId, SpeakerIdentityService.SpeakerResolution resolution) {
        SpeakerIdentityCallback callback = sessionSpeakerIdentityCallbackMap.get(sessionId);
        if (callback == null || resolution == null) {
            return;
        }
        callback.accept(
                resolution.getSpeakerId(),
                resolution.getPersonName(),
                resolution.getSpeakerProfileId(),
                resolution.getCartesiaVoiceId(),
                resolution.getStatus(),
                resolution.getSource()
        );
    }

    private boolean isPipelineActive(String sessionId, String stage) {
        boolean active = sessionService.isSessionActive(sessionId);
        if (!active) {
            log.info("[RealtimeInterpretationFacade] skip inactive session pipeline, sessionId={}, stage={}",
                    sessionId, stage);
        }
        return active;
    }

    private boolean isCompressionDirection(String sourceLang, String targetLang) {
        return Constants.LANG_ZH_CN.equalsIgnoreCase(sourceLang)
                && Constants.LANG_ID_SHORT.equalsIgnoreCase(targetLang);
    }

    private long estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        return Math.max(1L, Math.round(text.length() / 2.0));
    }

    private record TtsBufferedChunk(
            byte[] pcm,
            String lang,
            String taskId,
            long sequence,
            int chunkIndex,
            long createdAtNanos
    ) {
    }

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

    private double resolveTtsSpeed(String targetLang) {
        if (Constants.LANG_ID_SHORT.equalsIgnoreCase(targetLang)) {
            return Constants.TTS_SPEED_INDONESIAN;
        }
        if (Constants.LANG_EN_SHORT.equalsIgnoreCase(targetLang) || Constants.LANG_EN_US.equalsIgnoreCase(targetLang)) {
            return Constants.TTS_SPEED_ENGLISH;
        }
        return Constants.TTS_SPEED_DEFAULT;
    }

    // ---------- Cleanup ----------

    /**
     * 清理指定 WebSocket 会话的资源。
     */
    public void cleanupSession(String sessionId) {
        log.info("[RealtimeInterpretationFacade] cleanupSession, sessionId={}", sessionId);
        try {
            asrService.stopRecognition(sessionId);
        } catch (Exception e) {
            log.error("[RealtimeInterpretationFacade] stopRecognition failed, sessionId={}", sessionId, e);
        }
        try {
            if (sessionService.isSessionActive(sessionId)) {
                sessionService.stopSession(sessionId);
            }
        } catch (Exception e) {
            log.error("[RealtimeInterpretationFacade] stopSession failed, sessionId={}", sessionId, e);
        }
        sessionTranslatedCallbackMap.remove(sessionId);
        sessionTtsAudioCallbackMap.remove(sessionId);
        sessionErrorCallbackMap.remove(sessionId);
        sessionTtsChain.remove(sessionId);
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.remove(sessionId);
        sessionTtsQueueSize.remove(sessionId);
        sessionTtsSequence.remove(sessionId);
        recordService.cleanupSession(sessionId);
        sessionSpeakerVoiceService.cleanupSession(sessionId);
        speakerIdentityService.cleanupSession(sessionId);
        sessionSpeakerIdentityCallbackMap.remove(sessionId);
        log.info("[RealtimeInterpretationFacade] cleanupSession done, sessionId={}", sessionId);
    }

    /** Returns the resolved personName for a speakerId if already known for this session, null otherwise. */
    public String getCachedSpeakerName(String sessionId, String speakerId) {
        return speakerIdentityService.getCachedSpeakerName(sessionId, speakerId);
    }

    /**
     * 纯文本翻译（不触发 TTS），供 WebSocket 翻译文本消息使用。
     */
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
        void accept(byte[] pcmData, String targetLang, String ttsTaskId, long ttsSequence, int chunkIndex);
    }

    @FunctionalInterface
    public interface SpeakerIdentityCallback {
        void accept(String speakerId, String speakerName, String speakerProfileId, String cartesiaVoiceId, String status, String source);
    }
}
