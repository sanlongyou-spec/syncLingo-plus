package com.si.backend.facade;

import com.si.backend.common.Constants;
import com.si.backend.common.TtsStreamHandle;
import com.si.backend.config.CartesiaProperties;
import com.si.backend.entity.InterpretationRecord;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AsrService;
import com.si.backend.service.IndonesianIncompleteGuard;
import com.si.backend.service.AudioRecordService;
import com.si.backend.service.InterpretationRecordService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.SpeechDurationCalibrationService;
import com.si.backend.service.SpeakerTurnService;
import com.si.backend.service.TtsPcmSpeedService;
import com.si.backend.service.TtsService;
import com.si.backend.service.TtsTextNormalizer;
import com.si.backend.service.TranslationService;
import com.si.backend.service.UserVoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AudioRecordService audioRecordService;
    private final SpeakerTurnService speakerTurnService;
    private final UserVoiceService userVoiceService;
    private final IndonesianIncompleteGuard indonesianIncompleteGuard;
    private final SpeechDurationCalibrationService speechDurationCalibrationService;
    private final TtsPcmSpeedService ttsPcmSpeedService;

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
    /** 会话级印尼语(原文→中文译文)滑动上下文：供 id→zh LLM 纠错翻译消歧并保持术语/称谓/风格一致 */
    private final ConcurrentHashMap<String, java.util.ArrayDeque<String[]>> sessionIdSourceContext = new ConcurrentHashMap<>();
    /** 滑动上下文最大字符数 */
    private static final int MAX_ID_CONTEXT_CHARS = 600;
    /** 双语上下文最多保留的(印尼语→中文)对数，有界防 prompt 膨胀/延迟 */
    private static final int MAX_ID_CONTEXT_PAIRS = 3;
    private final ConcurrentHashMap<String, AtomicLong> sessionTtsQueueSize = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sessionTtsSequence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sessionUtteranceStartMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionCurrentSpeakerId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ManualVoiceBinding> sessionManualVoiceBindings = new ConcurrentHashMap<>();

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
        sessionCurrentSpeakerId.remove(sessionId);
        sessionManualVoiceBindings.remove(sessionId);
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

    public void setManualVoice(String sessionId, String speakerId, String voiceId) {
        log.info("[RealtimeInterpretationFacade] setManualVoice start, sessionId={}, speakerId={}, hasVoice={}",
                sessionId, speakerId, voiceId != null && !voiceId.isBlank());
        if (!sessionService.isSessionActive(sessionId)) {
            log.warn("[RealtimeInterpretationFacade] setManualVoice ignored inactive session, sessionId={}", sessionId);
            return;
        }
        if (voiceId == null || voiceId.isBlank()) {
            sessionManualVoiceBindings.remove(sessionId);
            log.info("[RealtimeInterpretationFacade] setManualVoice cleared, sessionId={}", sessionId);
            return;
        }
        String normalizedSpeakerId = normalizeSpeakerId(speakerId);
        if (normalizedSpeakerId == null) {
            log.warn("[RealtimeInterpretationFacade] setManualVoice rejected blank speaker, sessionId={}", sessionId);
            return;
        }
        Long userId = sessionService.getSession(sessionId)
                .map(InterpretationSession::getUserId)
                .orElseThrow(() -> com.si.backend.common.BizException.of(com.si.backend.common.ErrorCode.SESSION_NOT_FOUND));
        String resolvedVoiceId = userVoiceService.requireUsableVoice(userId, voiceId);
        sessionCurrentSpeakerId.put(sessionId, normalizedSpeakerId);
        sessionManualVoiceBindings.put(sessionId, new ManualVoiceBinding(normalizedSpeakerId, resolvedVoiceId));
        log.info("[RealtimeInterpretationFacade] setManualVoice end, sessionId={}, speakerId={}, voiceId={}",
                sessionId, normalizedSpeakerId, resolvedVoiceId);
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
        sessionIdSourceContext.remove(sessionId);
        sessionTtsQueueSize.remove(sessionId);
        sessionTtsSequence.remove(sessionId);
        sessionUtteranceStartMs.remove(sessionId);
        sessionCurrentSpeakerId.remove(sessionId);
        sessionManualVoiceBindings.remove(sessionId);
        audioRecordService.finalizeRecording(sessionId);
        recordService.cleanupSession(sessionId);
        speakerTurnService.flushSession(sessionId);
        speakerTurnService.cleanupSession(sessionId);
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

        // 根据 speakerId 跟踪说话人切换，切换确认后触发异步发言摘要
        speakerTurnService.processRecognized(sessionId, speakerId, text);
        observeSpeakerChange(sessionId, speakerId);

        String sourceLang = normalizeAsrLang(detectedLang);
        List<String> targetLangs = resolveTargetLangs(sessionId, sourceLang);
        log.info("[RealtimeInterpretationFacade] processFinalRecognition, sessionId={}, speakerId={}, detected={}, sourceLang={}, targetLangs={}",
                sessionId, speakerId, detectedLang, sourceLang, targetLangs);

        String prevLang = sessionLastSourceLang.put(sessionId, sourceLang);
        if (prevLang != null && !prevLang.equals(sourceLang)) {
            log.info("[RealtimeInterpretationFacade] lang switch {}->{}, sessionId={}", prevLang, sourceLang, sessionId);
        }

        final String finalVoiceId = resolveManualVoiceId(sessionId, speakerId);

        targetLangs.forEach(targetLang -> {
            if (!isPipelineActive(sessionId, "before_target_translate")) return;
            TtsPlaybackReservation reservation = reserveTtsPlayback(sessionId, targetLang);
            CompletableFuture.runAsync(
                    () -> translateAndStreamTts(text, sourceLang, targetLang, finalVoiceId,
                            sessionId, speakerId, speakerId, speechStartAtMs, reservation),
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

        boolean indonesianSource = sourceLang != null && sourceLang.trim().toLowerCase().startsWith("id");
        String translated;
        try {
            Long userId = sessionService.getSession(sessionId).map(InterpretationSession::getUserId).orElse(1L);
            Long meetingId = sessionService.getSession(sessionId).map(InterpretationSession::getMeetingId).orElse(null);
            // 纵深防御:id 源文本进翻译/入库/TTS 前再过一次完整性 Guard,拦住任何漏网的半词/残句。
            if (indonesianSource && indonesianIncompleteGuard.isEnabled()) {
                IndonesianIncompleteGuard.GuardResult guardResult = indonesianIncompleteGuard.check(text);
                if (!guardResult.isPass()) {
                    log.info("[IdGuard] facade suppress id source, decision={}, reason={}, sessionId={}, textLen={}",
                            guardResult.decision(), guardResult.reason(), sessionId, text.length());
                    completeTtsReservation(reservation, "id_guard_blocked");
                    return;
                }
            }
            String recentContext = indonesianSource ? recentIdContext(sessionId, text) : null;
            translated = translationService.translate(text, sourceLang, targetLang, userId, meetingId, wantCompress, recentContext);
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
        if (indonesianSource) {
            // 记录(印尼语原文 → 中文译文)成对上下文，供后续句 id→zh 纠错翻译保持术语/称谓/风格一致
            appendIdContext(sessionId, text, translated);
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
        InterpretationRecord record = recordService.saveTranslatedRecord(sessionId, sourceLang, targetLang, text, translated);

        TranslationResultCallback onTranslated = sessionTranslatedCallbackMap.get(sessionId);
        if (onTranslated != null && isPipelineActive(sessionId, "before_translated_callback")) {
            onTranslated.accept(text, translated, sourceLang, targetLang, speakerId, speakerName);
        }

        if (!isPipelineActive(sessionId, "before_tts_queue")) {
            completeTtsReservation(reservation, "inactive_before_tts");
            return;
        }
        final String resolvedVoiceId = resolveVoiceId(voiceId, targetLang, sessionId, speakerId);
        final String finalTranslated = translated;
        final String finalTargetLang = targetLang;
        final Long recordId = record != null ? record.getId() : null;
        final Integer recordSeq = record != null ? record.getSeq() : null;
        final TtsTextNormalizer.Result ttsTextResult =
                TtsTextNormalizer.normalizeForTts(finalTranslated, finalTargetLang);
        final String finalTtsText = ttsTextResult.text();
        final String ttsTextHash = diagnosticHash(finalTtsText);
        final long ttsSequence = reservation.sequence();
        final String ttsTaskId = reservation.taskId();
        final double cartesiaSynthesisSpeed = Constants.CARTESIA_TTS_SYNTHESIS_SPEED;
        final TtsPcmSpeedService.PcmSpeedProcessor pcmSpeedProcessor =
                ttsPcmSpeedService.processor(finalTargetLang);
        final double backendPcmSpeed = pcmSpeedProcessor.speed();
        final int ttsSampleRate = cartesiaProperties.getTts().getSampleRate();
        final long maxForwardAudioMs = TtsTextNormalizer.maxForwardAudioMs(finalTtsText, finalTargetLang);
        final SpeechDurationCalibrationService.Estimate durationEstimate =
                speechDurationCalibrationService.estimate(finalTargetLang, finalTtsText);

        if (ttsTextResult.changed()) {
            log.info("[RealtimeInterpretationFacade] TTS text normalized, sessionId={}, taskId={}, sequence={}, recordId={}, recordSeq={}, textHash={}, originalLen={}, ttsLen={}, originalPreview='{}', ttsPreview='{}'",
                    sessionId, ttsTaskId, ttsSequence, recordId, recordSeq, ttsTextHash,
                    finalTranslated.length(), finalTtsText.length(),
                    previewForLog(finalTranslated, 120), previewForLog(finalTtsText, 120));
        }
        log.info("[RealtimeInterpretationFacade] TTS queued, sessionId={}, taskId={}, sequence={}, recordId={}, recordSeq={}, textHash={}, textLen={}, ttsTextLen={}, ttsTextChanged={}, maxForwardAudioMs={}, estimatedAudioMs={}, calibrationWordSamples={}, calibrationCharSamples={}, voiceId={}, cartesiaSpeed={}, backendPcmSpeed={}, prevDone={}",
                sessionId, ttsTaskId, ttsSequence, recordId, recordSeq, ttsTextHash,
                finalTranslated.length(), finalTtsText.length(),
                ttsTextResult.changed(), maxForwardAudioMs, durationEstimate.estimateMs(),
                durationEstimate.wordSamples(), durationEstimate.charSamples(),
                resolvedVoiceId, cartesiaSynthesisSpeed, backendPcmSpeed, reservation.previous().isDone());

        BlockingQueue<TtsBufferedChunk> audioQueue = new LinkedBlockingQueue<>();
        final AtomicLong firstChunkGenMs = new AtomicLong(0);
        final AtomicLong receivedPcmBytes = new AtomicLong(0);
        final AtomicLong forwardedPcmBytes = new AtomicLong(0);
        final AtomicLong receivedChunkCounter = new AtomicLong(0);
        final AtomicLong forwardedChunkCounter = new AtomicLong(0);
        final AtomicBoolean ttsAudioTruncated = new AtomicBoolean(false);
        final AtomicReference<String> terminalReason = new AtomicReference<>();
        final AtomicReference<TtsStreamHandle> ttsStreamHandle = new AtomicReference<>(TtsStreamHandle.NOOP);
        final AtomicReference<String> pendingCancelReason = new AtomicReference<>();

        CompletableFuture.runAsync(() -> {
            if (!isPipelineActive(sessionId, "before_tts_synth")) {
                completeTtsReservation(reservation, "inactive_before_tts_synth");
                return;
            }

            long ttsStart = System.currentTimeMillis();
            AtomicBoolean firstChunkLogged = new AtomicBoolean(false);
            TtsStreamHandle handle = ttsService.synthesizeStream(
                    resolvedVoiceId, finalTtsText, cartesiaProperties.getTts().getSampleRate(),
                    cartesiaSynthesisSpeed, cartesiaLanguage(finalTargetLang),
                    pcm -> {
                        long receivedChunks = receivedChunkCounter.incrementAndGet();
                        long receivedBytes = receivedPcmBytes.addAndGet(pcm.length);
                        byte[] acceleratedPcm = pcmSpeedProcessor.process(pcm);
                        if (acceleratedPcm == null || acceleratedPcm.length == 0) {
                            return;
                        }
                        long nextForwardAudioMs = TtsTextNormalizer.pcmDurationMs(
                                forwardedPcmBytes.get() + acceleratedPcm.length, ttsSampleRate);
                        if (maxForwardAudioMs > 0 && nextForwardAudioMs > maxForwardAudioMs) {
                            if (ttsAudioTruncated.compareAndSet(false, true)) {
                                String cancelReason = "duration guard: taskId=" + ttsTaskId;
                                terminalReason.compareAndSet(null, "duration_guard");
                                pendingCancelReason.compareAndSet(null, cancelReason);
                                log.warn("[RealtimeInterpretationFacade] TTS audio duration guard triggered, sessionId={}, taskId={}, sequence={}, recordId={}, recordSeq={}, textHash={}, receivedChunks={}, receivedAudioMs={}, forwardedAudioMs={}, maxForwardAudioMs={}, backendPcmSpeed={}, textLen={}, ttsTextLen={}, ttsPreview='{}'",
                                        sessionId, ttsTaskId, ttsSequence, recordId, recordSeq, ttsTextHash, receivedChunks,
                                        TtsTextNormalizer.pcmDurationMs(receivedBytes, ttsSampleRate),
                                        TtsTextNormalizer.pcmDurationMs(forwardedPcmBytes.get(), ttsSampleRate),
                                        maxForwardAudioMs, backendPcmSpeed, finalTranslated.length(), finalTtsText.length(),
                                        previewForLog(finalTtsText, 120));
                                ttsStreamHandle.get().cancel(cancelReason);
                            }
                            return;
                        }
                        if (!reservation.completion().isDone() && sessionService.isSessionActive(sessionId)) {
                            int chunkIndex = Math.toIntExact(forwardedChunkCounter.getAndIncrement());
                            forwardedPcmBytes.addAndGet(acceleratedPcm.length);
                            if (firstChunkLogged.compareAndSet(false, true)) {
                                long firstChunkMs = System.currentTimeMillis();
                                firstChunkGenMs.set(firstChunkMs);
                                log.info("[RealtimeInterpretationFacade] TTS first chunk, sessionId={}, taskId={}, sequence={}, prevDoneAtFirstChunk={}, costMs={}, rawBytes={}, forwardedBytes={}, backendPcmSpeed={}",
                                        sessionId, ttsTaskId, ttsSequence, reservation.previous().isDone(),
                                        firstChunkMs - ttsStart, pcm.length, acceleratedPcm.length, backendPcmSpeed);
                                log.info("[RealtimeInterpretationFacade] e2e speech-to-tts(server), sessionId={}, targetLang={}, captureToFirstAudioMs={}, textLen={}",
                                        sessionId, finalTargetLang, firstChunkMs - speechStartAtMs, finalTtsText.length());
                                log.info("[RealtimeInterpretationFacade] latency-breakdown, sessionId={}, taskId={}, sequence={}, lang={}, asrMs={}, translateMs={}, gapMs={}, ttsMs={}, totalMs={}, textLen={}",
                                        sessionId, ttsTaskId, ttsSequence, finalTargetLang,
                                        translateStart - speechStartAtMs,
                                        translateDoneMs - translateStart,
                                        ttsStart - translateDoneMs,
                                        firstChunkMs - ttsStart,
                                        firstChunkMs - speechStartAtMs,
                                        finalTtsText.length());
                            }
                            audioQueue.offer(new TtsBufferedChunk(acceleratedPcm, finalTargetLang, ttsTaskId, ttsSequence, chunkIndex, System.nanoTime()));
                        }
                    },
                    () -> {
                        try {
                            log.info("[RealtimeInterpretationFacade] TTS synth complete, sessionId={}, taskId={}, sequence={}, chunks={}, forwardedChunks={}, costMs={}, cartesiaSpeed={}, backendPcmSpeed={}",
                                    sessionId, ttsTaskId, ttsSequence, receivedChunkCounter.get(),
                                    forwardedChunkCounter.get(), System.currentTimeMillis() - ttsStart,
                                    cartesiaSynthesisSpeed, backendPcmSpeed);
                            long audioDurationMs = TtsTextNormalizer.pcmDurationMs(receivedPcmBytes.get(), ttsSampleRate);
                            long forwardedAudioDurationMs = TtsTextNormalizer.pcmDurationMs(forwardedPcmBytes.get(), ttsSampleRate);
                            log.info("[RealtimeInterpretationFacade] tts-audio-duration, sessionId={}, taskId={}, sequence={}, recordId={}, recordSeq={}, textHash={}, lang={}, audioDurationMs={}, forwardedAudioDurationMs={}, maxForwardAudioMs={}, truncated={}, cartesiaSpeed={}, backendPcmSpeed={}, sourceSpeechWindowMs={}, textLen={}, ttsTextLen={}",
                                    sessionId, ttsTaskId, ttsSequence, recordId, recordSeq, ttsTextHash, finalTargetLang, audioDurationMs,
                                    forwardedAudioDurationMs, maxForwardAudioMs, ttsAudioTruncated.get(),
                                    cartesiaSynthesisSpeed, backendPcmSpeed, translateStart - speechStartAtMs,
                                    finalTranslated.length(), finalTtsText.length());
                            speechDurationCalibrationService.recordActualDuration(
                                    finalTargetLang,
                                    finalTtsText,
                                    forwardedAudioDurationMs,
                                    ttsAudioTruncated.get());
                        } finally {
                            terminalReason.compareAndSet(null, "synth_complete");
                            audioQueue.offer(TTS_END);
                        }
                    },
                    err -> {
                        try {
                            log.error("[RealtimeInterpretationFacade] TTS synth error, sessionId={}, taskId={}, sequence={}, error={}",
                                    sessionId, ttsTaskId, ttsSequence, err);
                        } finally {
                            terminalReason.compareAndSet(null, "synth_error");
                            audioQueue.offer(TTS_END);
                        }
                    });
            ttsStreamHandle.set(handle != null ? handle : TtsStreamHandle.NOOP);
            String cancelReason = pendingCancelReason.get();
            if (cancelReason != null) {
                ttsStreamHandle.get().cancel(cancelReason);
            }
            try {
                long orderedWaitStart = System.currentTimeMillis();
                awaitPreviousTtsReservation(reservation);
                long orderedWaitMs = System.currentTimeMillis() - orderedWaitStart;
                log.info("[RealtimeInterpretationFacade] TTS playback order ready, sessionId={}, taskId={}, sequence={}, orderedWaitMs={}, synthesizedAhead={}",
                        sessionId, ttsTaskId, ttsSequence, orderedWaitMs, firstChunkGenMs.get() > 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                completeTtsReservation(reservation, "ordered_wait_interrupted");
                return;
            }
            if (!isPipelineActive(sessionId, "before_tts_playback")) {
                completeTtsReservation(reservation, "inactive_before_tts_playback");
                return;
            }

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
                        String reason = terminalReason.get() == null ? "synth_complete" : terminalReason.get();
                        log.info("[RealtimeInterpretationFacade] TTS stream complete, sessionId={}, taskId={}, sequence={}, terminalReason={}, costMs={}",
                                sessionId, ttsTaskId, ttsSequence, reason, System.currentTimeMillis() - playbackStart);
                        completeTtsReservation(reservation, reason.equals("synth_complete") ? "stream_complete" : reason);
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
            log.error("[RealtimeInterpretationFacade] TTS playback task error, sessionId={}", sessionId, ex);
            completeTtsReservation(reservation, "playback_chain_error");
            return null;
        });
    }

    private void awaitPreviousTtsReservation(TtsPlaybackReservation reservation) throws InterruptedException {
        if (reservation.previous().isDone()) {
            return;
        }
        try {
            reservation.previous().get();
        } catch (ExecutionException e) {
            log.warn("[RealtimeInterpretationFacade] previous TTS reservation completed exceptionally, sessionId={}, taskId={}, reason={}",
                    reservation.sessionId(), reservation.taskId(), e.getMessage());
        }
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

    private static String previewForLog(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static String diagnosticHash(String text) {
        return text == null ? "null" : Integer.toHexString(text.hashCode());
    }

    private record TtsBufferedChunk(
            byte[] pcm, String lang, String taskId, long sequence, int chunkIndex, long createdAtNanos) {}

    private record TtsPlaybackReservation(
            String sessionId, String lang, String taskId, long sequence,
            CompletableFuture<Void> previous, CompletableFuture<Void> completion, AtomicBoolean released) {}

    private String resolveVoiceId(String voiceId, String targetLang, String sessionId, String speakerId) {
        if (voiceId != null && !voiceId.isBlank()) {
            log.info("[RealtimeInterpretationFacade] resolveVoiceId, sessionId={}, speakerId={}, targetLang={}, reason=explicit, voiceId={}",
                    sessionId, speakerId, targetLang, voiceId);
            return voiceId;
        }
        // 固定使用目标语言的母语男声(已移除性别检测)。男声音色由 CARTESIA_*_MALE_VOICE_ID 配置。
        CartesiaProperties.VoiceGenderTtsProperties voiceProps = cartesiaProperties.getVoiceGender();
        String maleVoiceId = voiceProps == null ? null
                : voiceProps.maleVoiceIdForLanguage(cartesiaLanguage(targetLang));
        if (maleVoiceId != null && !maleVoiceId.isBlank()) {
            log.info("[RealtimeInterpretationFacade] resolveVoiceId, sessionId={}, speakerId={}, targetLang={}, reason=male, voiceId={}",
                    sessionId, speakerId, targetLang, maleVoiceId.trim());
            return maleVoiceId.trim();
        }
        if (Constants.LANG_ZH_CN.equalsIgnoreCase(targetLang) || Constants.LANG_CLONE_ZH.equalsIgnoreCase(targetLang)) {
            String resolved = cartesiaProperties.getDefaultVoiceIdChinese();
            log.info("[RealtimeInterpretationFacade] resolveVoiceId, sessionId={}, speakerId={}, targetLang={}, reason=target-default-zh, voiceId={}",
                    sessionId, speakerId, targetLang, resolved);
            return resolved;
        }
        if (Constants.LANG_EN_SHORT.equalsIgnoreCase(targetLang)
                || Constants.LANG_EN_US.equalsIgnoreCase(targetLang)
                || Constants.LANG_CLONE_EN.equalsIgnoreCase(targetLang)) {
            String englishVoiceId = cartesiaProperties.getDefaultVoiceIdEnglish();
            if (englishVoiceId == null || englishVoiceId.isBlank()) {
                log.warn("[RealtimeInterpretationFacade] English voice is blank, fallback to default voice");
                return Constants.VOICE_ID_DEFAULT;
            }
            log.info("[RealtimeInterpretationFacade] resolveVoiceId, sessionId={}, speakerId={}, targetLang={}, reason=target-default-en, voiceId={}",
                    sessionId, speakerId, targetLang, englishVoiceId);
            return englishVoiceId;
        }
        String resolved = cartesiaProperties.getDefaultVoiceIdIndonesian();
        log.info("[RealtimeInterpretationFacade] resolveVoiceId, sessionId={}, speakerId={}, targetLang={}, reason=target-default-id, voiceId={}",
                sessionId, speakerId, targetLang, resolved);
        return resolved;
    }

    private String cartesiaLanguage(String targetLang) {
        if (targetLang == null) return null;
        String lower = targetLang.toLowerCase();
        if (lower.startsWith("id") || lower.startsWith("in")) return "id";
        if (lower.startsWith(Constants.LANG_EN_SHORT)) return "en";
        if (lower.startsWith("zh")) return "zh";
        return null;
    }

    private void observeSpeakerChange(String sessionId, String speakerId) {
        String normalizedSpeakerId = normalizeSpeakerId(speakerId);
        if (normalizedSpeakerId == null) {
            return;
        }
        String previous = sessionCurrentSpeakerId.put(sessionId, normalizedSpeakerId);
        if (previous != null && !previous.equals(normalizedSpeakerId)) {
            sessionManualVoiceBindings.remove(sessionId);
            log.info("[RealtimeInterpretationFacade] speaker changed, manual voice reset, sessionId={}, previousSpeakerId={}, currentSpeakerId={}",
                    sessionId, previous, normalizedSpeakerId);
        }
    }

    private String resolveManualVoiceId(String sessionId, String speakerId) {
        ManualVoiceBinding binding = sessionManualVoiceBindings.get(sessionId);
        if (binding == null) {
            return null;
        }
        String normalizedSpeakerId = normalizeSpeakerId(speakerId);
        if (normalizedSpeakerId != null && normalizedSpeakerId.equals(binding.speakerId())) {
            log.info("[RealtimeInterpretationFacade] manual voice selected, sessionId={}, speakerId={}, voiceId={}",
                    sessionId, normalizedSpeakerId, binding.voiceId());
            return binding.voiceId();
        }
        log.info("[RealtimeInterpretationFacade] manual voice skipped, sessionId={}, currentSpeakerId={}, boundSpeakerId={}, reason=speaker-mismatch",
                sessionId, normalizedSpeakerId, binding.speakerId());
        return null;
    }

    private String normalizeSpeakerId(String speakerId) {
        String normalized = speakerId == null ? "" : speakerId.trim();
        if (normalized.isBlank() || Constants.SPEAKER_ID_UNKNOWN.equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private record ManualVoiceBinding(String speakerId, String voiceId) {
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
        sessionIdSourceContext.remove(sessionId);
        sessionTtsQueueSize.remove(sessionId);
        sessionTtsSequence.remove(sessionId);
        sessionUtteranceStartMs.remove(sessionId);
        sessionCurrentSpeakerId.remove(sessionId);
        sessionManualVoiceBindings.remove(sessionId);
        audioRecordService.finalizeRecording(sessionId);
        recordService.cleanupSession(sessionId);
        speakerTurnService.flushSession(sessionId);
        speakerTurnService.cleanupSession(sessionId);
        log.info("[RealtimeInterpretationFacade] cleanupSession done, sessionId={}", sessionId);
    }

    /** 取该会话最近的(印尼语原文→中文译文)对作为上下文(排除当前句)，供 id→zh LLM 纠错翻译消歧并保持一致。 */
    private String recentIdContext(String sessionId, String currentText) {
        java.util.ArrayDeque<String[]> buffer = sessionIdSourceContext.get(sessionId);
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }
        StringBuilder context = new StringBuilder();
        synchronized (buffer) {
            for (String[] pair : buffer) {
                String id = pair[0];
                String zh = pair.length > 1 ? pair[1] : null;
                if (id == null || id.equals(currentText)) {
                    continue;
                }
                if (context.length() > 0) {
                    context.append('\n');
                }
                context.append("印尼语: ").append(id);
                if (zh != null && !zh.isBlank()) {
                    context.append("\n中文: ").append(zh);
                }
            }
        }
        return context.length() == 0 ? null : context.toString();
    }

    /** 把(印尼语原文→中文译文)对追加进会话上下文(同句只记一次)，并按对数与字符数双重上界裁剪旧句。 */
    private void appendIdContext(String sessionId, String id, String zh) {
        if (id == null || id.isBlank()) {
            return;
        }
        java.util.ArrayDeque<String[]> buffer =
                sessionIdSourceContext.computeIfAbsent(sessionId, k -> new java.util.ArrayDeque<>());
        synchronized (buffer) {
            String[] last = buffer.peekLast();
            if (last != null && id.equals(last[0])) {
                return;   // 同句被多目标语言重复翻译时，只记一次
            }
            buffer.addLast(new String[]{id, zh == null ? "" : zh});
            while (buffer.size() > MAX_ID_CONTEXT_PAIRS) {
                buffer.pollFirst();
            }
            int total = 0;
            for (String[] pair : buffer) {
                total += pair[0].length() + (pair[1] == null ? 0 : pair[1].length()) + 2;
            }
            while (total > MAX_ID_CONTEXT_CHARS && buffer.size() > 1) {
                String[] removed = buffer.pollFirst();
                total -= removed[0].length() + (removed[1] == null ? 0 : removed[1].length()) + 2;
            }
        }
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
