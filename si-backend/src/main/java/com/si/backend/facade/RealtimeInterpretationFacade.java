package com.si.backend.facade;

import com.si.backend.common.Constants;
import com.si.backend.config.CartesiaProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AsrService;
import com.si.backend.service.AudioRoutingService;
import com.si.backend.service.InterpretationSessionService;
import com.si.backend.service.TtsService;
import com.si.backend.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时同传门面层，协调 ASR、翻译、TTS 的实时处理流程。
 * 所有业务逻辑委托给 AsrService / TtsService，禁止直接调用 Integration 层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeInterpretationFacade {

    private final AsrService asrService;
    private final TtsService ttsService;
    private final TranslationService translationService;
    private final InterpretationSessionService sessionService;
    private final AudioRoutingService audioRoutingService;
    private final CartesiaProperties cartesiaProperties;

    /** 缓存每个会话最近一次 ASR 检测到的归一化语种，用于原声通道路由 */
    private final ConcurrentHashMap<String, String> sessionDetectedLangCache = new ConcurrentHashMap<>();

    /** 缓存每个会话的翻译结果回调，用于将译文推送给前端 */
    private final ConcurrentHashMap<String, TranslationResultCallback> sessionTranslatedCallbackMap = new ConcurrentHashMap<>();

    /** 缓存每个会话的 TTS 音频回调，用于将 PCM 数据推送给前端 */
    private final ConcurrentHashMap<String, TtsAudioCallback> sessionTtsAudioCallbackMap = new ConcurrentHashMap<>();

    /** 每个会话最后一个 TTS 任务的 Future，串行化多段 TTS 防止声音混叠 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionTtsChain = new ConcurrentHashMap<>();

    // ---------- WebSocket session lifecycle ----------

    /**
     * 启动实时 ASR 识别与翻译。
     *
     * @param sessionId   WebSocket 会话 ID
     * @param sourceLang  源语言（传 "auto" 启用自动检测）
     * @param targetLang  目标语言
     * @param voiceId     音色 ID（可为空）
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
            AsrErrorCallback onError
    ) {
        log.info("[RealtimeInterpretationFacade] startInterpretation start, sessionId={}, sourceLang={}, targetLang={}, voiceId={}",
                sessionId, sourceLang, targetLang, voiceId);

        sessionTranslatedCallbackMap.put(sessionId, onTranslated);
        sessionTtsAudioCallbackMap.put(sessionId, onTtsAudio);

        // 初始化 VoiceMeeter 源音直通
        if (audioRoutingService.isEnabled()) {
            // 发送一个静音帧初始化设备
            audioRoutingService.writeSourceAudio(new byte[320], 16000, "zh");
        }

        // 启动 ASR（自动检测语种或指定语种）
        asrService.startRecognition(
                sessionId,
                sourceLang,
                (text, lang) -> onRecognizing.accept(text, lang),
                (text, lang) -> {
                    onRecognized.accept(text, lang);
                    processFinalRecognition(text, lang, sessionId, voiceId);
                },
                errorMessage -> onError.accept(errorMessage)
        );

        log.info("[RealtimeInterpretationFacade] startInterpretation end, sessionId={}", sessionId);
    }

    /**
     * 推送音频帧到 ASR，并透传原音到 VoiceMeeter。
     *
     * @param sessionId WebSocket 会话 ID
     * @param pcmFrame PCM 音频帧
     */
    public void pushAudioAndPassthrough(String sessionId, byte[] pcmFrame) {
        log.info("[RealtimeInterpretationFacade] pushAudioAndPassthrough start, sessionId={}, bytes={}",
                sessionId, pcmFrame.length);
        asrService.pushAudio(sessionId, pcmFrame);

        if (audioRoutingService.isEnabled()) {
            String cachedLang = sessionDetectedLangCache.get(sessionId);
            audioRoutingService.writeSourceAudio(pcmFrame, 16000, cachedLang != null ? cachedLang : "zh");
        }
        log.info("[RealtimeInterpretationFacade] pushAudioAndPassthrough end, sessionId={}, bytes={}",
                sessionId, pcmFrame.length);
    }

    /**
     * 停止实时 ASR 识别。
     *
     * @param sessionId WebSocket 会话 ID
     */
    public void stopInterpretation(String sessionId) {
        log.info("[RealtimeInterpretationFacade] stopInterpretation start, sessionId={}", sessionId);
        asrService.stopRecognition(sessionId);
        sessionService.stopSession(sessionId);
        sessionDetectedLangCache.remove(sessionId);
        sessionTranslatedCallbackMap.remove(sessionId);
        sessionTtsAudioCallbackMap.remove(sessionId);
        sessionTtsChain.remove(sessionId);
        log.info("[RealtimeInterpretationFacade] stopInterpretation end, sessionId={}", sessionId);
    }

    // ---------- Recognition → Translation → TTS pipeline ----------

    /**
     * 处理最终识别结果：根据检测到的语种自动选择翻译方向，然后 TTS 播报。
     *
     * @param text        识别的原文
     * @param detectedLang ASR 自动检测出的语种（zh / id 等）
     * @param sessionId   WebSocket 会话 ID
     * @param voiceId     音色 ID
     */
    public void processFinalRecognition(String text, String detectedLang, String sessionId, String voiceId) {
        log.info("[RealtimeInterpretationFacade] processFinalRecognition start, sessionId={}, detectedLang={}", sessionId, detectedLang);
        if (text == null || text.isBlank()) {
            log.info("[RealtimeInterpretationFacade] processFinalRecognition skip, empty text, sessionId={}", sessionId);
            return;
        }
        String sourceLang = normalizeAsrLang(detectedLang);
        String targetLang = Constants.LANG_ZH_CN.equalsIgnoreCase(sourceLang)
                ? Constants.LANG_ID_SHORT
                : Constants.LANG_ZH_CN;

        sessionDetectedLangCache.put(sessionId, sourceLang);

        log.info("[RealtimeInterpretationFacade] processFinalRecognition, sessionId={}, textLen={}, detected={}, {}→{}",
                sessionId, text.length(), detectedLang, sourceLang, targetLang);

        String resolvedVoiceId = voiceId;
        if (resolvedVoiceId == null || resolvedVoiceId.isBlank()) {
            resolvedVoiceId = sessionService.getSession(sessionId)
                    .map(InterpretationSession::getVoiceId)
                    .orElse(null);
        }

        translateAndStreamTts(text, sourceLang, targetLang, resolvedVoiceId, sessionId);
        log.info("[RealtimeInterpretationFacade] processFinalRecognition end, sessionId={}", sessionId);
    }

    private String normalizeAsrLang(String asrLang) {
        log.debug("[RealtimeInterpretationFacade] normalizeAsrLang start, asrLang={}", asrLang);
        if (asrLang == null) {
            log.debug("[RealtimeInterpretationFacade] normalizeAsrLang end, result={}", Constants.LANG_ZH_CN);
            return Constants.LANG_ZH_CN;
        }
        String lower = asrLang.toLowerCase();
        String result;
        if (lower.startsWith("zh") || lower.startsWith("zh-hans")) {
            result = Constants.LANG_ZH_CN;
        } else if (lower.startsWith("id")) {
            result = Constants.LANG_ID_SHORT;
        } else {
            result = Constants.LANG_ZH_CN;
        }
        log.debug("[RealtimeInterpretationFacade] normalizeAsrLang end, asrLang={}, result={}", asrLang, result);
        return result;
    }

    // ---------- Translation + TTS ----------

    /**
     * 翻译文本并流式合成 TTS 音频。
     *
     * @param text        原文
     * @param sourceLang  源语言
     * @param targetLang  目标语言
     * @param voiceId     音色 ID（可为空，使用默认音色）
     * @param sessionId   WebSocket 会话 ID
     */
    public void translateAndStreamTts(String text, String sourceLang, String targetLang, String voiceId, String sessionId) {
        if (text == null || text.isBlank()) {
            return;
        }
        log.info("[RealtimeInterpretationFacade] translateAndStreamTts start, sessionId={}, textLen={}, {}→{}, voiceId={}",
                sessionId, text.length(), sourceLang, targetLang, voiceId);

        // 翻译
        long translateStart = System.currentTimeMillis();
        String translated = translationService.translate(text, sourceLang, targetLang);
        long translateCost = System.currentTimeMillis() - translateStart;

        if (translated == null || translated.isBlank()) {
            log.warn("[RealtimeInterpretationFacade] translateAndStreamTts skip, empty translation, sessionId={}", sessionId);
            return;
        }
        log.info("[RealtimeInterpretationFacade] translate cost, sessionId={}, costMs={}, translatedLen={}",
                sessionId, translateCost, translated.length());

        TranslationResultCallback onTranslated = sessionTranslatedCallbackMap.get(sessionId);
        if (onTranslated != null) {
            onTranslated.accept(text, translated, targetLang);
        }

        // 解析音色 ID
        final String resolvedVoiceId = resolveVoiceId(voiceId, targetLang);
        final String finalTranslated = translated;
        final String finalTargetLang = targetLang;

        // 串行化 TTS：原子替换链尾，等待上一个 TTS 完成后再开始本次
        CompletableFuture<Void> thisFuture = new CompletableFuture<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] prevHolder = new CompletableFuture[1];
        sessionTtsChain.compute(sessionId, (k, prev) -> {
            prevHolder[0] = (prev != null) ? prev : CompletableFuture.completedFuture(null);
            return thisFuture;
        });

        log.info("[RealtimeInterpretationFacade] TTS queued, sessionId={}, textLen={}, voiceId={}, prevDone={}",
                sessionId, finalTranslated.length(), resolvedVoiceId, prevHolder[0].isDone());

        prevHolder[0].thenRunAsync(() -> {
            long ttsStart = System.currentTimeMillis();
            log.info("[RealtimeInterpretationFacade] TTS stream start, sessionId={}, textLen={}, voiceId={}",
                    sessionId, finalTranslated.length(), resolvedVoiceId);

            ttsService.synthesizeStream(
                    resolvedVoiceId,
                    finalTranslated,
                    cartesiaProperties.getTts().getSampleRate(),
                    pcm -> {
                        if (audioRoutingService.isEnabled()) {
                            audioRoutingService.writeTargetAudio(pcm, 24000, finalTargetLang);
                        }
                        TtsAudioCallback onTtsAudio = sessionTtsAudioCallbackMap.get(sessionId);
                        if (onTtsAudio != null) {
                            onTtsAudio.accept(pcm, finalTargetLang);
                        }
                    },
                    () -> {
                        long cost = System.currentTimeMillis() - ttsStart;
                        log.info("[RealtimeInterpretationFacade] TTS stream complete, sessionId={}, costMs={}", sessionId, cost);
                        thisFuture.complete(null);
                    },
                    err -> {
                        log.error("[RealtimeInterpretationFacade] TTS stream error, sessionId={}, error={}", sessionId, err);
                        thisFuture.complete(null); // 出错也完成，防止后续 TTS 永久阻塞
                    }
            );
        });

        log.info("[RealtimeInterpretationFacade] translateAndStreamTts end, sessionId={}", sessionId);
    }

    private String resolveVoiceId(String voiceId, String targetLang) {
        log.debug("[RealtimeInterpretationFacade] resolveVoiceId start, voiceId={}, targetLang={}", voiceId, targetLang);
        String resolved;
        if (voiceId != null && !voiceId.isBlank()) {
            resolved = voiceId;
        } else if (Constants.LANG_ZH_CN.equalsIgnoreCase(targetLang) || Constants.LANG_CLONE_ZH.equalsIgnoreCase(targetLang)) {
            resolved = Constants.DEFAULT_VOICE_ID_CHINESE;
        } else {
            resolved = Constants.DEFAULT_VOICE_ID_INDONESIAN;
        }
        log.debug("[RealtimeInterpretationFacade] resolveVoiceId end, resolved={}", resolved);
        return resolved;
    }

    // ---------- Cleanup ----------

    /**
     * 清理指定 WebSocket 会话的资源。
     *
     * @param sessionId WebSocket 会话 ID
     */
    public void cleanupSession(String sessionId) {
        log.info("[RealtimeInterpretationFacade] cleanupSession start, sessionId={}", sessionId);
        asrService.stopRecognition(sessionId);
        if (sessionService.isSessionActive(sessionId)) {
            sessionService.stopSession(sessionId);
        }
        sessionDetectedLangCache.remove(sessionId);
        sessionTranslatedCallbackMap.remove(sessionId);
        sessionTtsAudioCallbackMap.remove(sessionId);
        sessionTtsChain.remove(sessionId);
        log.info("[RealtimeInterpretationFacade] cleanupSession end, sessionId={}", sessionId);
    }

    /**
     * 纯文本翻译（不触发 TTS），供 WebSocket 翻译文本消息使用。
     *
     * @param text       待翻译文本
     * @param targetLang 目标语言
     * @return 译文
     */
    public String translateText(String text, String targetLang) {
        if (text == null || text.isBlank()) {
            return "";
        }
        log.info("[RealtimeInterpretationFacade] translateText, textLen={}, targetLang={}",
                text.length(), targetLang);
        long start = System.currentTimeMillis();
        String translated = translationService.translate(text, Constants.LANG_AUTO, targetLang);
        long cost = System.currentTimeMillis() - start;
        log.info("[RealtimeInterpretationFacade] translateText end, textLen={}, targetLang={}, costMs={}, resultLen={}",
                text.length(), targetLang, cost, translated != null ? translated.length() : 0);
        return translated;
    }

    @FunctionalInterface
    public interface AsrRecognitionCallback {
        void accept(String text, String language);
    }

    @FunctionalInterface
    public interface AsrErrorCallback {
        void accept(String errorMessage);
    }

    @FunctionalInterface
    public interface TranslationResultCallback {
        void accept(String originalText, String translatedText, String targetLang);
    }

    @FunctionalInterface
    public interface TtsAudioCallback {
        void accept(byte[] pcmData, String targetLang);
    }
}
