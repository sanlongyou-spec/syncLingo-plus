package com.si.backend.integration;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.AzureSpeechProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Azure ASR 集成层，封装 Azure 语音识别 API 调用。
 * 支持自动语种检测（AutoDetectSourceLanguageConfig）与指定语种两种模式。
 */
@Slf4j
@Component
public class AzureAsrIntegration {

    private final AzureSpeechProperties asrProperties;
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

    public AzureAsrIntegration(AzureSpeechProperties asrProperties) {
        this.asrProperties = asrProperties;
    }

    /**
     * 创建 ASR 会话。
     *
     * @param sessionId  会话 ID
     * @param sourceLang 源语言，传 "auto" 或 null 启用自动检测
     * @return ASR 会话实例
     */
    public AsrSession createSession(String sessionId, String sourceLang) {
        if (sessions.containsKey(sessionId)) {
            log.warn("[AzureAsrIntegration] session already exists, sessionId={}", sessionId);
            return sessions.get(sessionId);
        }

        SpeechConfig config = SpeechConfig.fromSubscription(
                asrProperties.getKey(),
                asrProperties.getRegion()
        );

        config.setProperty(PropertyId.SpeechServiceConnection_EndSilenceTimeoutMs,
                String.valueOf(asrProperties.getAsr().getEndSilenceTimeoutMs()));

        AudioStreamFormat audioFormat = AudioStreamFormat.getWaveFormatPCM(
                (short) asrProperties.getAsr().getSampleRate(),
                (short) Constants.BITS_PER_SAMPLE,
                (short) Constants.AUDIO_CHANNELS_MONO
        );

        PushAudioInputStream pushStream = PushAudioInputStream.create(audioFormat);

        AsrSession session;
        if (Constants.LANG_AUTO.equalsIgnoreCase(sourceLang) || sourceLang == null || sourceLang.isBlank()) {
            log.info("[AzureAsrIntegration] creating session with AutoDetectSourceLanguageConfig, sessionId={}", sessionId);
            String[] languages = parseLanguages(asrProperties.getAsr().getLanguage());
            AutoDetectSourceLanguageConfig autoConfig = AutoDetectSourceLanguageConfig.fromLanguages(List.of(languages));
            session = new AsrSession(config, autoConfig, pushStream);
        } else {
            log.info("[AzureAsrIntegration] creating session with specified lang={}, sessionId={}", sourceLang, sessionId);
            config.setSpeechRecognitionLanguage(sourceLang);
            AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
            session = new AsrSession(config, audioConfig, pushStream);
        }

        sessions.put(sessionId, session);
        log.info("[AzureAsrIntegration] session created, sessionId={}", sessionId);
        return session;
    }

    /**
     * 兼容旧调用，使用配置默认值。
     */
    public AsrSession createSession(String sessionId) {
        return createSession(sessionId, null);
    }

    /**
     * 推送 PCM 音频帧。
     *
     * @param sessionId 会话 ID
     * @param audioData PCM 数据
     */
    public void pushAudio(String sessionId, byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            return;
        }
        AsrSession session = sessions.get(sessionId);
        if (session != null && session.pushStream != null) {
            session.pushStream.write(audioData);
        }
    }

    /**
     * 关闭并清理会话。
     *
     * @param sessionId 会话 ID
     */
    public void closeSession(String sessionId) {
        AsrSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            log.info("[AzureAsrIntegration] session closed, sessionId={}", sessionId);
        }
    }

    private String[] parseLanguages(String languageConfig) {
        if (languageConfig == null || languageConfig.isBlank()) {
            return Constants.ASR_DEFAULT_LANGUAGES.split(",");
        }
        return languageConfig.split(",");
    }

    // ─────────────────────────────────────────────────────────
    // ASR 会话封装
    // ─────────────────────────────────────────────────────────

    /**
     * ASR 会话封装。
     */
    public static class AsrSession implements AutoCloseable {

        private final SpeechConfig config;
        private final PushAudioInputStream pushStream;
        private final SpeechRecognizer recognizer;
        private final boolean autoDetectEnabled;
        private final AtomicReference<String> finalText = new AtomicReference<>("");
        private final AtomicReference<String> detectedLang = new AtomicReference<>("");
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private RecognizerCallback callback;

        public AsrSession(SpeechConfig config, AudioConfig audioConfig, PushAudioInputStream pushStream) {
            this.config = config;
            this.pushStream = pushStream;
            this.autoDetectEnabled = false;
            this.recognizer = new SpeechRecognizer(config, audioConfig);
        }

        public AsrSession(SpeechConfig config, AutoDetectSourceLanguageConfig autoConfig, PushAudioInputStream pushStream) {
            this.config = config;
            this.pushStream = pushStream;
            this.autoDetectEnabled = true;
            AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
            this.recognizer = new SpeechRecognizer(config, autoConfig, audioConfig);
        }

        public void setCallback(RecognizerCallback callback) {
            this.callback = callback;
        }

        public void startContinuous() {
            recognizer.recognizing.addEventListener((s, e) -> {
                if (callback != null) {
                    String lang = resolveDetectedLanguage(e.getResult());
                    callback.onRecognizing(e.getResult().getText(), lang, false);
                }
            });

            recognizer.recognized.addEventListener((s, e) -> {
                if (callback != null) {
                    boolean isFinal = e.getResult().getReason() == ResultReason.RecognizedSpeech;
                    if (isFinal) {
                        String lang = resolveDetectedLanguage(e.getResult());
                        callback.onRecognizing(e.getResult().getText(), lang, true);
                    }
                }
            });

            recognizer.canceled.addEventListener((s, e) -> {
                log.warn("[AsrSession] ASR canceled, reason={}, error={}", e.getReason(), e.getErrorDetails());
                if (callback != null) {
                    callback.onError(e.getErrorDetails());
                }
            });

            recognizer.sessionStopped.addEventListener((s, e) -> closedLatch.countDown());

            recognizer.startContinuousRecognitionAsync();
        }

        private String resolveDetectedLanguage(SpeechRecognitionResult result) {
            if (autoDetectEnabled) {
                AutoDetectSourceLanguageResult autoResult = AutoDetectSourceLanguageResult.fromResult(result);
                String lang = autoResult.getLanguage();
                if (lang != null && !lang.isBlank()) {
                    detectedLang.set(lang);
                    return lang;
                }
            }
            return config.getSpeechRecognitionLanguage() != null
                    ? config.getSpeechRecognitionLanguage()
                    : Constants.LANG_ZH_CN;
        }

        public String recognizeOnce() throws Exception {
            log.info("[AsrSession] recognizeOnce start");

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("");
            AtomicReference<String> lang = new AtomicReference<>("");

            recognizer.recognized.addEventListener((s, e) -> {
                if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                    result.set(e.getResult().getText());
                    lang.set(resolveDetectedLanguage(e.getResult()));
                }
                latch.countDown();
            });
            recognizer.canceled.addEventListener((s, e) -> latch.countDown());

            long start = System.currentTimeMillis();
            recognizer.recognizeOnceAsync().get(10, TimeUnit.SECONDS);
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            long cost = System.currentTimeMillis() - start;

            if (!completed) {
                throw BizException.of(ErrorCode.ASR_RECOGNIZE_ERROR, Constants.ASR_TIMEOUT_ERROR);
            }
            detectedLang.set(lang.get());
            finalText.set(result.get());

            log.info("[AsrSession] recognizeOnce end, textLen={}, lang={}, costMs={}",
                    result.get() != null ? result.get().length() : 0, lang.get(), cost);
            return result.get();
        }

        public String getFinalText() {
            return finalText.get();
        }

        public String getDetectedLang() {
            return detectedLang.get();
        }

        @Override
        public void close() {
            try {
                recognizer.stopContinuousRecognitionAsync().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[AsrSession] stop recognizer error", e);
            }
            recognizer.close();
            pushStream.close();
            config.close();
        }
    }

    public interface RecognizerCallback {
        void onRecognizing(String text, String language, boolean isFinal);
        void onError(String errorMessage);
    }
}
