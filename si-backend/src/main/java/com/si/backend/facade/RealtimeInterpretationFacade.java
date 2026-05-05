package com.si.backend.facade;

import com.si.backend.common.Constants;
import com.si.backend.config.CartesiaProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.service.AsrService;
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
            AsrErrorCallback onError
    ) {
        log.info("[RealtimeInterpretationFacade] startInterpretation, sessionId={}, sourceLang={}, targetLang={}, voiceId={}",
                sessionId, sourceLang, targetLang, voiceId);

        sessionTranslatedCallbackMap.put(sessionId, onTranslated);
        sessionTtsAudioCallbackMap.put(sessionId, onTtsAudio);

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
    }

    /**
     * 推送音频帧到 ASR。
     *
     * @param sessionId WebSocket 会话 ID
     * @param pcmFrame  PCM 音频帧
     */
    public void pushAudio(String sessionId, byte[] pcmFrame) {
        asrService.pushAudio(sessionId, pcmFrame);
    }

    /**
     * 停止实时 ASR 识别。
     *
     * @param sessionId WebSocket 会话 ID
     */
    public void stopInterpretation(String sessionId) {
        log.info("[RealtimeInterpretationFacade] stopInterpretation, sessionId={}", sessionId);
        asrService.stopRecognition(sessionId);
        sessionService.stopSession(sessionId);
        sessionTranslatedCallbackMap.remove(sessionId);
        sessionTtsAudioCallbackMap.remove(sessionId);
        sessionTtsChain.remove(sessionId);
    }

    // ---------- Recognition → Translation → TTS pipeline ----------

    /**
     * 处理最终识别结果：根据检测到的语种自动选择翻译方向，然后 TTS 播报。
     */
    public void processFinalRecognition(String text, String detectedLang, String sessionId, String voiceId) {
        if (text == null || text.isBlank()) return;

        String sourceLang = normalizeAsrLang(detectedLang);
        String targetLang = Constants.LANG_ZH_CN.equalsIgnoreCase(sourceLang)
                ? Constants.LANG_ID_SHORT
                : Constants.LANG_ZH_CN;

        log.info("[RealtimeInterpretationFacade] processFinalRecognition, sessionId={}, detected={}, {}→{}",
                sessionId, detectedLang, sourceLang, targetLang);

        String resolvedVoiceId = voiceId;
        if (resolvedVoiceId == null || resolvedVoiceId.isBlank()) {
            resolvedVoiceId = sessionService.getSession(sessionId)
                    .map(InterpretationSession::getVoiceId)
                    .orElse(null);
        }

        translateAndStreamTts(text, sourceLang, targetLang, resolvedVoiceId, sessionId);
    }

    private String normalizeAsrLang(String asrLang) {
        if (asrLang == null) return Constants.LANG_ZH_CN;
        String lower = asrLang.toLowerCase();
        if (lower.startsWith("zh") || lower.startsWith("zh-hans")) return Constants.LANG_ZH_CN;
        if (lower.startsWith("id")) return Constants.LANG_ID_SHORT;
        return Constants.LANG_ZH_CN;
    }

    // ---------- Translation + TTS ----------

    /**
     * 翻译文本并流式合成 TTS 音频，通过回调推送给前端。
     */
    public void translateAndStreamTts(String text, String sourceLang, String targetLang, String voiceId, String sessionId) {
        if (text == null || text.isBlank()) return;

        log.info("[RealtimeInterpretationFacade] translateAndStreamTts, sessionId={}, textLen={}, {}→{}, voiceId={}",
                sessionId, text.length(), sourceLang, targetLang, voiceId);

        long translateStart = System.currentTimeMillis();
        String translated = translationService.translate(text, sourceLang, targetLang);
        log.info("[RealtimeInterpretationFacade] translate done, sessionId={}, costMs={}, translatedLen={}",
                sessionId, System.currentTimeMillis() - translateStart, translated != null ? translated.length() : 0);

        if (translated == null || translated.isBlank()) return;

        TranslationResultCallback onTranslated = sessionTranslatedCallbackMap.get(sessionId);
        if (onTranslated != null) {
            onTranslated.accept(text, translated, targetLang);
        }

        translated = truncateIfTooLong(translated, 150);

        final String resolvedVoiceId = resolveVoiceId(voiceId, targetLang);
        final String finalTranslated = translated;
        final String finalTargetLang = targetLang;

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
            ttsService.synthesizeStream(
                    resolvedVoiceId,
                    finalTranslated,
                    cartesiaProperties.getTts().getSampleRate(),
                    pcm -> {
                        TtsAudioCallback onTtsAudio = sessionTtsAudioCallbackMap.get(sessionId);
                        if (onTtsAudio != null) {
                            onTtsAudio.accept(pcm, finalTargetLang);
                        }
                    },
                    () -> {
                        log.info("[RealtimeInterpretationFacade] TTS stream complete, sessionId={}, costMs={}",
                                sessionId, System.currentTimeMillis() - ttsStart);
                        thisFuture.complete(null);
                    },
                    err -> {
                        log.error("[RealtimeInterpretationFacade] TTS stream error, sessionId={}, error={}", sessionId, err);
                        thisFuture.complete(null);
                    }
            );
        });
    }

    private String truncateIfTooLong(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        int cutAt = maxLen;
        for (int i = maxLen; i >= maxLen / 2; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == ',' || c == ';' || c == '!' || c == '?') {
                cutAt = i + 1;
                break;
            }
        }
        String truncated = text.substring(0, cutAt).trim();
        log.info("[RealtimeInterpretationFacade] truncated: {}→{} chars", text.length(), truncated.length());
        return truncated;
    }

    private String resolveVoiceId(String voiceId, String targetLang) {
        if (voiceId != null && !voiceId.isBlank()) return voiceId;
        if (Constants.LANG_ZH_CN.equalsIgnoreCase(targetLang) || Constants.LANG_CLONE_ZH.equalsIgnoreCase(targetLang)) {
            return Constants.DEFAULT_VOICE_ID_CHINESE;
        }
        return Constants.DEFAULT_VOICE_ID_INDONESIAN;
    }

    // ---------- Cleanup ----------

    /**
     * 清理指定 WebSocket 会话的资源。
     */
    public void cleanupSession(String sessionId) {
        log.info("[RealtimeInterpretationFacade] cleanupSession, sessionId={}", sessionId);
        asrService.stopRecognition(sessionId);
        if (sessionService.isSessionActive(sessionId)) {
            sessionService.stopSession(sessionId);
        }
        sessionTranslatedCallbackMap.remove(sessionId);
        sessionTtsAudioCallbackMap.remove(sessionId);
        sessionTtsChain.remove(sessionId);
    }

    /**
     * 纯文本翻译（不触发 TTS），供 WebSocket 翻译文本消息使用。
     */
    public String translateText(String text, String targetLang) {
        if (text == null || text.isBlank()) return "";
        log.info("[RealtimeInterpretationFacade] translateText, textLen={}, targetLang={}", text.length(), targetLang);
        return translationService.translate(text, Constants.LANG_AUTO, targetLang);
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
