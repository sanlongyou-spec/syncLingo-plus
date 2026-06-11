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
 * TTS/原始音频经服务器 Opus 编码后由 ShareAudioWebSocketHandler 按听众所选语言分发到分享页。
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

    /** 每个会话每种目标语言最后一个 TTS 播放任务的 Future；key = sessionId::targetLang，同语言串行，跨语言并行 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionTtsChain = new ConcurrentHashMap<>();

    /** 每个会话的 final 识别初始化链，保证按 ASR final 到达顺序预留 TTS 播放位置。 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionFinalRecognitionChain = new ConcurrentHashMap<>();

    private static final TtsBufferedChunk TTS_END = new TtsBufferedChunk(null, null, null, -1L, -1, -1L);

    /** 每个会话上一次检测到的源语言，用于判断是否发生了语言切换 */
    private final ConcurrentHashMap<String, String> sessionLastSourceLang = new ConcurrentHashMap<>();

    /** Session-level requested target language; auto means translating to the other two languages. */
    private final ConcurrentHashMap<String, String> sessionTargetLangMap = new ConcurrentHashMap<>();

    /** 每个会话当前排队 TTS 数量，用于积压控制 */
    private final ConcurrentHashMap<String, AtomicLong> sessionTtsQueueSize = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sessionTtsSequence = new ConcurrentHashMap<>();

    /** 每个会话当前这句的"开始被收音"时刻（第一帧 recognizing），0=当前无进行中的句子。用于端到端延迟统计。 */
    private final ConcurrentHashMap<String, AtomicLong> sessionUtteranceStartMs = new ConcurrentHashMap<>();

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

        // 重置 TTS 链：清除该会话所有语言的旧链，防止 WS 重连时旧 Future 阻塞新任务
        sessionTtsChain.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + "::"));
        sessionFinalRecognitionChain.remove(sessionId);
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.put(sessionId, normalizeTargetLang(targetLang));
        sessionTtsQueueSize.computeIfAbsent(sessionId, key -> new AtomicLong(0)).set(0);
        sessionTtsSequence.computeIfAbsent(sessionId, key -> new AtomicLong(0)).set(0);
        sessionUtteranceStartMs.computeIfAbsent(sessionId, key -> new AtomicLong(0)).set(0);
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
                (text, lang, speakerId) -> {
                    // 记录"该句开始被收音"的时刻：本句第一帧 recognizing（上一句 final 后第一次）
                    sessionUtteranceStartMs.computeIfAbsent(sessionId, k -> new AtomicLong(0))
                            .compareAndSet(0, System.currentTimeMillis());
                    onRecognizing.accept(text, lang, speakerId);
                },
                (text, lang, speakerId) -> {
                    onRecognized.accept(text, lang, speakerId);
                    // 端到端延迟起点：该句"开始被收音"的时刻；取出并清零，下一帧 recognizing 开启新一句
                    long startedMs = sessionUtteranceStartMs.computeIfAbsent(sessionId, k -> new AtomicLong(0))
                            .getAndSet(0);
                    long speechStartAtMs = startedMs > 0 ? startedMs : System.currentTimeMillis();
                    enqueueFinalRecognition(text, lang, speakerId, sessionId, voiceId, speechStartAtMs);
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
        // Capture userId before session is removed from active map
        Long userId = sessionService.getSession(sessionId)
                .map(s -> s.getUserId())
                .orElse(null);
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
        sessionTtsChain.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + "::"));
        sessionFinalRecognitionChain.remove(sessionId);
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.remove(sessionId);
        sessionTtsQueueSize.remove(sessionId);
        sessionTtsSequence.remove(sessionId);
        sessionUtteranceStartMs.remove(sessionId);
        recordService.cleanupSession(sessionId);
        sessionSpeakerVoiceService.cleanupSession(sessionId);
        speakerIdentityService.cleanupSession(sessionId);
        sessionSpeakerIdentityCallbackMap.remove(sessionId);
        log.info("[RealtimeInterpretationFacade] stopInterpretation done, sessionId={}", sessionId);
    }

    // ---------- Recognition → Translation → TTS pipeline ----------

    private void enqueueFinalRecognition(
            String text,
            String detectedLang,
            String speakerId,
            String sessionId,
            String voiceId,
            long speechStartAtMs
    ) {
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
                if (errCb != null) {
                    errCb.accept("翻译处理失败: " + ex.getMessage());
                }
                return null;
            });
        });
    }

    /**
     * 处理最终识别结果：根据检测到的语种自动选择翻译方向，然后 TTS 播报。
     */
    public void processFinalRecognition(String text, String detectedLang, String speakerId, String sessionId, String voiceId) {
        processFinalRecognition(text, detectedLang, speakerId, sessionId, voiceId, System.currentTimeMillis());
    }

    public void processFinalRecognition(String text, String detectedLang, String speakerId, String sessionId, String voiceId, long speechStartAtMs) {
        if (text == null || text.isBlank()) return;
        if (!isPipelineActive(sessionId, "process_final_start")) return;

        String sourceLang = normalizeAsrLang(detectedLang);
        List<String> targetLangs = resolveTargetLangs(sessionId, sourceLang);
        sessionSpeakerVoiceService.collectAndCloneIfNeeded(sessionId, speakerId, sourceLang);
        // 声纹识别移出关键路径：本句立即用缓存/上一句/默认身份，不阻塞翻译；
        // 真正的网络声纹识别异步执行，结果更新缓存供后续语句使用（首次出现的说话人本句用默认音色）。
        SpeakerIdentityService.SpeakerResolution speakerResolution =
                speakerIdentityService.resolveCached(sessionId, speakerId);
        notifySpeakerIdentity(sessionId, speakerResolution);
        final byte[] speakerIdentityPcm = resolveSpeakerIdentityPcm(sessionId, speakerId);
        CompletableFuture.runAsync(() -> {
            long identifyStart = System.currentTimeMillis();
            SpeakerIdentityService.SpeakerResolution full =
                    speakerIdentityService.resolveOrIdentify(sessionId, speakerId, speakerIdentityPcm);
            log.info("[RealtimeInterpretationFacade] speakerIdentify async done, sessionId={}, speakerId={}, personName={}, speakerIdentifyMs={}",
                    sessionId, speakerId, full.getPersonName(), System.currentTimeMillis() - identifyStart);
            if (isPipelineActive(sessionId, "speaker_identify_async")) {
                notifySpeakerIdentity(sessionId, full);
            }
        }, TRANSLATION_EXECUTOR).exceptionally(ex -> {
            log.warn("[RealtimeInterpretationFacade] speakerIdentify async error, sessionId={}, speakerId={}", sessionId, speakerId, ex);
            return null;
        });

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
        final String finalVoiceId = resolvedVoiceId;

        // Each target language is translated and TTS'd in parallel:
        // - text arrives at the frontend for all languages at roughly the same time
        // - each language has its own TTS chain, so audio plays independently per channel
        targetLangs.forEach(targetLang -> {
            if (!isPipelineActive(sessionId, "before_target_translate")) return;
            TtsPlaybackReservation reservation = reserveTtsPlayback(sessionId, targetLang);
            CompletableFuture.runAsync(
                    () -> translateAndStreamTts(
                            text, sourceLang, targetLang, finalVoiceId, sessionId, speakerId,
                            speakerResolution, speechStartAtMs, reservation
                    ),
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
        translateAndStreamTts(
                text, sourceLang, targetLang, voiceId, sessionId, speakerId, speakerResolution,
                System.currentTimeMillis()
        );
    }

    public void translateAndStreamTts(
            String text,
            String sourceLang,
            String targetLang,
            String voiceId,
            String sessionId,
            String speakerId,
            SpeakerIdentityService.SpeakerResolution speakerResolution,
            long speechStartAtMs
    ) {
        if (text == null || text.isBlank()) return;
        TtsPlaybackReservation reservation = reserveTtsPlayback(sessionId, targetLang);
        try {
            translateAndStreamTts(
                    text, sourceLang, targetLang, voiceId, sessionId, speakerId,
                    speakerResolution, speechStartAtMs, reservation
            );
        } catch (RuntimeException e) {
            completeTtsReservation(reservation, "translate_pipeline_exception");
            throw e;
        }
    }

    private void translateAndStreamTts(
            String text,
            String sourceLang,
            String targetLang,
            String voiceId,
            String sessionId,
            String speakerId,
            SpeakerIdentityService.SpeakerResolution speakerResolution,
            long speechStartAtMs,
            TtsPlaybackReservation reservation
    ) {
        if (text == null || text.isBlank()) {
            completeTtsReservation(reservation, "blank_text");
            return;
        }
        if (!isPipelineActive(sessionId, "translate_start")) {
            completeTtsReservation(reservation, "inactive_translate_start");
            return;
        }

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
            onTranslated.accept(text, translated, sourceLang, targetLang, speakerId, speakerResolution.getPersonName());
        }

        if (!isPipelineActive(sessionId, "before_tts_queue")) {
            completeTtsReservation(reservation, "inactive_before_tts");
            return;
        }
        String identityVoiceId = speakerResolution != null ? speakerResolution.getCartesiaVoiceId() : null;
        if (identityVoiceId == null || identityVoiceId.isBlank()) {
            identityVoiceId = speakerIdentityService.resolveVoiceId(sessionId, speakerId);
        }
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
        final long ttsSequence = reservation.sequence();
        final String ttsTaskId = reservation.taskId();

        log.info("[RealtimeInterpretationFacade] TTS queued, sessionId={}, taskId={}, sequence={}, textLen={}, voiceId={}, prevDone={}",
                sessionId, ttsTaskId, ttsSequence, finalTranslated.length(), resolvedVoiceId, reservation.previous().isDone());

        final double ttsSpeed = resolveTtsSpeed(targetLang);

        BlockingQueue<TtsBufferedChunk> audioQueue = new LinkedBlockingQueue<>();
        final AtomicLong firstChunkGenMs = new AtomicLong(0);  // 首块"生成"时刻：度量 生成→实际发送 的排队等待
        final AtomicLong totalPcmBytes = new AtomicLong(0);     // 整句 TTS PCM 字节累计：算真实音频时长
        final int ttsSampleRate = cartesiaProperties.getTts().getSampleRate();

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
                        if (!reservation.completion().isDone() && sessionService.isSessionActive(sessionId)) {
                            int chunkIndex = Math.toIntExact(chunkCounter.getAndIncrement());
                            totalPcmBytes.addAndGet(pcm.length);
                            if (firstChunkLogged.compareAndSet(false, true)) {
                                long firstChunkMs = System.currentTimeMillis();
                                firstChunkGenMs.set(firstChunkMs);
                                log.info("[RealtimeInterpretationFacade] TTS first chunk, sessionId={}, taskId={}, sequence={}, costMs={}, bytes={}",
                                        sessionId, ttsTaskId, ttsSequence, firstChunkMs - ttsStart, pcm.length);
                                // 端到端(服务端口径)：该句开始被收音 → 该句该语言 TTS 首音从服务器发出
                                log.info("[RealtimeInterpretationFacade] e2e speech-to-tts(server), sessionId={}, targetLang={}, captureToFirstAudioMs={}, textLen={}",
                                        sessionId, finalTargetLang, firstChunkMs - speechStartAtMs, finalTranslated.length());
                                // 分段耗时：asr(含说话时长+静音判定) / 翻译 / 排队 / TTS首音(生成口径)；带 taskId/sequence 便于按句去重
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
                            // 真实音频时长(按 PCM 字节算, 不是字符) + 说话窗口, 用于压缩目标的 时长口径 度量
                            long audioDurationMs = ttsSampleRate > 0
                                    ? totalPcmBytes.get() * 1000L / ((long) ttsSampleRate * 2L)
                                    : -1L;
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
                    }
            );
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
                        log.warn("[RealtimeInterpretationFacade] TTS playback timeout waiting chunk, sessionId={}, timeoutSeconds={}",
                                sessionId, Constants.TTS_STREAM_TIMEOUT_SECONDS);
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
                        if (chunkGapNanos > 0) {
                            sleepUntil(lastSendTimeNanos + chunkGapNanos);
                        }
                    }

                    TtsAudioCallback onTtsAudio = sessionTtsAudioCallbackMap.get(sessionId);
                    if (onTtsAudio != null) {
                        onTtsAudio.accept(chunk.pcm(), chunk.lang(), chunk.taskId(), chunk.sequence(), chunk.chunkIndex(), speechStartAtMs);
                    }
                    if (!firstSentLogged) {
                        firstSentLogged = true;
                        long sentMs = System.currentTimeMillis();
                        long gen = firstChunkGenMs.get();
                        // 真实"说话开始→首音实际发出"(含 生成→发送 的串行排队等待, 之前 latency-breakdown 漏掉的最大一块)
                        log.info("[RealtimeInterpretationFacade] tts-first-chunk-sent, sessionId={}, taskId={}, sequence={}, captureToSentMs={}, sendQueueWaitMs={}",
                                sessionId, chunk.taskId(), chunk.sequence(),
                                sentMs - speechStartAtMs,
                                gen > 0 ? sentMs - gen : -1L);
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
        long sequence = sessionTtsSequence
                .computeIfAbsent(sessionId, key -> new AtomicLong(0))
                .incrementAndGet();
        String taskId = sessionId + "-" + sequence;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] previousHolder = new CompletableFuture[1];
        String chainKey = sessionId + "::" + targetLang;
        sessionTtsChain.compute(chainKey, (key, previous) -> {
            previousHolder[0] = previous != null ? previous : CompletableFuture.completedFuture(null);
            return completion;
        });
        long queueSize = sessionTtsQueueSize
                .computeIfAbsent(sessionId, key -> new AtomicLong(0))
                .incrementAndGet();
        if (queueSize > TTS_QUEUE_LIMIT) {
            log.warn("[RealtimeInterpretationFacade] TTS queue backlog high, keep queue to avoid skip, sessionId={}, queueSize={}, warnLimit={}",
                    sessionId, queueSize, TTS_QUEUE_LIMIT);
        }
        log.info("[RealtimeInterpretationFacade] TTS order reserved, sessionId={}, lang={}, taskId={}, sequence={}, prevDone={}",
                sessionId, targetLang, taskId, sequence, previousHolder[0].isDone());
        return new TtsPlaybackReservation(
                sessionId,
                targetLang,
                taskId,
                sequence,
                previousHolder[0],
                completion,
                new AtomicBoolean(false)
        );
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

    private byte[] resolveSpeakerIdentityPcm(String sessionId, String speakerId) {
        if (isUnknownSpeakerId(speakerId)) {
            return sessionSpeakerVoiceService.getRecentSessionPcm(sessionId, Constants.SPEAKER_VOICE_IDENTIFY_WINDOW_SECONDS);
        }
        return sessionSpeakerVoiceService.getSpeakerAudioPcm(sessionId, speakerId);
    }

    private boolean isUnknownSpeakerId(String speakerId) {
        return speakerId != null && Constants.SPEAKER_ID_UNKNOWN.equalsIgnoreCase(speakerId.trim());
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

    private record TtsPlaybackReservation(
            String sessionId,
            String lang,
            String taskId,
            long sequence,
            CompletableFuture<Void> previous,
            CompletableFuture<Void> completion,
            AtomicBoolean released
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
        // 合成阶段一律用自然语速(1.0)：加速只在前端按播放积压驱动(变调 playbackRate, 最高 1.5x)，
        // 避免无积压时也把译音说得很赶。targetLang 暂保留以便将来按语种微调。
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
        sessionTtsChain.entrySet().removeIf(e -> e.getKey().startsWith(sessionId + "::"));
        sessionFinalRecognitionChain.remove(sessionId);
        sessionLastSourceLang.remove(sessionId);
        sessionTargetLangMap.remove(sessionId);
        sessionTtsQueueSize.remove(sessionId);
        sessionTtsSequence.remove(sessionId);
        sessionUtteranceStartMs.remove(sessionId);
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
        void accept(byte[] pcmData, String targetLang, String ttsTaskId, long ttsSequence, int chunkIndex, long speechStartAtMs);
    }

    @FunctionalInterface
    public interface SpeakerIdentityCallback {
        void accept(String speakerId, String speakerName, String speakerProfileId, String cartesiaVoiceId, String status, String source);
    }
}
