package com.si.backend.integration;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriber;
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriptionResult;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    public AsrSession createSession(String sessionId, String sourceLang, List<String> hotwords, String enabledLanguages) {
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
        config.setProperty(PropertyId.Speech_SegmentationSilenceTimeoutMs,
                String.valueOf(asrProperties.getAsr().getSegmentationSilenceTimeoutMs()));
        if (asrProperties.getAsr().getSegmentationStrategy() != null
                && !asrProperties.getAsr().getSegmentationStrategy().isBlank()) {
            config.setProperty(PropertyId.Speech_SegmentationStrategy,
                    asrProperties.getAsr().getSegmentationStrategy());
        }
        if (asrProperties.getAsr().getSegmentationMaximumTimeMs() > 0) {
            config.setProperty("Speech_SegmentationMaximumTimeMs",
                    String.valueOf(asrProperties.getAsr().getSegmentationMaximumTimeMs()));
        }
        log.info("[AzureAsrIntegration] ASR silence config, endSilenceMs={}, segmentationSilenceMs={}, segmentationStrategy={}, segmentationMaxTimeMs={}, maxSegmentChars={}",
                asrProperties.getAsr().getEndSilenceTimeoutMs(),
                asrProperties.getAsr().getSegmentationSilenceTimeoutMs(),
                asrProperties.getAsr().getSegmentationStrategy(),
                asrProperties.getAsr().getSegmentationMaximumTimeMs(),
                asrProperties.getAsr().getMaxSegmentChars());

        AudioStreamFormat audioFormat = AudioStreamFormat.getWaveFormatPCM(
                (short) asrProperties.getAsr().getSampleRate(),
                (short) Constants.BITS_PER_SAMPLE,
                (short) Constants.AUDIO_CHANNELS_MONO
        );

        PushAudioInputStream pushStream = PushAudioInputStream.create(audioFormat);

        int maxSegmentChars = asrProperties.getAsr().getMaxSegmentChars();
        AsrSession session;
        if (Constants.LANG_AUTO.equalsIgnoreCase(sourceLang) || sourceLang == null || sourceLang.isBlank()) {
            log.info("[AzureAsrIntegration] creating session with ConversationTranscriber (Continuous LID + diarization), sessionId={}", sessionId);
            String[] languages = parseLanguages(
                    enabledLanguages != null && !enabledLanguages.isBlank()
                            ? enabledLanguages
                            : asrProperties.getAsr().getLanguage()
            );
            config.setProperty(PropertyId.SpeechServiceConnection_LanguageIdMode, "Continuous");
            AutoDetectSourceLanguageConfig autoConfig = AutoDetectSourceLanguageConfig.fromLanguages(List.of(languages));
            session = new AsrSession(config, autoConfig, pushStream, maxSegmentChars, hotwords);
        } else {
            log.info("[AzureAsrIntegration] creating session with ConversationTranscriber, specified lang={}, sessionId={}", sourceLang, sessionId);
            config.setSpeechRecognitionLanguage(sourceLang);
            AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
            session = new AsrSession(config, audioConfig, pushStream, maxSegmentChars, hotwords);
        }

        sessions.put(sessionId, session);
        log.info("[AzureAsrIntegration] session created, sessionId={}", sessionId);
        return session;
    }

    /**
     * 兼容旧调用，使用配置默认值。
     */
    public AsrSession createSession(String sessionId) {
        return createSession(sessionId, null, List.of(), null);
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
        private final ConversationTranscriber conversationTranscriber;
        private final boolean autoDetectEnabled;
        private final boolean diarizationEnabled;
        private final AtomicReference<String> finalText = new AtomicReference<>("");
        private final AtomicReference<String> detectedLang = new AtomicReference<>("");
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private RecognizerCallback callback;
        /** 应用层强制切段阈值（字符数），0 = 不限制 */
        private final int maxSegmentChars;
        /** 是否已触发强制切段并等待 Azure 真正的 transcribed 事件 */
        private final AtomicBoolean forcedFinalPending = new AtomicBoolean(false);
        /** 已强制下发的文本长度，用于从 transcribed 结果中截取余下部分 */
        private final AtomicInteger forcedFinalLength = new AtomicInteger(0);

        private final List<String> hotwords;

        public AsrSession(SpeechConfig config, AudioConfig audioConfig, PushAudioInputStream pushStream, int maxSegmentChars, List<String> hotwords) {
            this.config = config;
            this.pushStream = pushStream;
            this.autoDetectEnabled = false;
            this.diarizationEnabled = true;
            this.recognizer = null;
            this.maxSegmentChars = maxSegmentChars;
            this.hotwords = hotwords;
            this.conversationTranscriber = new ConversationTranscriber(config, audioConfig);
        }

        public AsrSession(SpeechConfig config, AutoDetectSourceLanguageConfig autoConfig, PushAudioInputStream pushStream, int maxSegmentChars, List<String> hotwords) {
            this.config = config;
            this.pushStream = pushStream;
            this.autoDetectEnabled = true;
            this.diarizationEnabled = true;
            this.maxSegmentChars = maxSegmentChars;
            this.hotwords = hotwords;
            AudioConfig audioConfig = AudioConfig.fromStreamInput(pushStream);
            this.recognizer = null;
            this.conversationTranscriber = new ConversationTranscriber(config, autoConfig, audioConfig);
        }

        public void setCallback(RecognizerCallback callback) {
            this.callback = callback;
        }

        public void startContinuous() {
            if (diarizationEnabled) {
                startConversationTranscriber();
                return;
            }
            recognizer.recognizing.addEventListener((s, e) -> {
                if (callback != null) {
                    String lang = resolveDetectedLanguage(e.getResult());
                    callback.onRecognizing(e.getResult().getText(), lang, Constants.SPEAKER_ID_UNKNOWN, false);
                }
            });

            recognizer.recognized.addEventListener((s, e) -> {
                if (callback != null) {
                    boolean isFinal = e.getResult().getReason() == ResultReason.RecognizedSpeech;
                    if (isFinal) {
                        String lang = resolveDetectedLanguage(e.getResult());
                        callback.onRecognizing(e.getResult().getText(), lang, Constants.SPEAKER_ID_UNKNOWN, true);
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

        private void startConversationTranscriber() {
            applyHotwords();
            conversationTranscriber.transcribing.addEventListener((s, e) -> {
                if (callback == null) return;
                ConversationTranscriptionResult result = e.getResult();
                String text = result.getText();
                if (text == null || text.isBlank()) return;
                // 强制切段：中途超过字数阈值时立即作为 final 下发
                if (maxSegmentChars > 0
                        && text.length() >= maxSegmentChars
                        && forcedFinalPending.compareAndSet(false, true)) {
                    forcedFinalLength.set(text.length());
                    log.info("[AsrSession] force-segment at {} chars", text.length());
                    callback.onRecognizing(text, resolveDetectedLanguage(result), resolveSpeakerId(result), true);
                    return;
                }
                // 已触发强制切段，抑制后续中间结果直到 transcribed 事件
                if (forcedFinalPending.get()) return;
                callback.onRecognizing(text, resolveDetectedLanguage(result), resolveSpeakerId(result), false);
            });

            conversationTranscriber.transcribed.addEventListener((s, e) -> {
                if (callback == null) return;
                ConversationTranscriptionResult result = e.getResult();
                if (result.getReason() != ResultReason.RecognizedSpeech) return;
                String text = result.getText();
                String lang = resolveDetectedLanguage(result);
                String speakerId = resolveSpeakerId(result);
                if (forcedFinalPending.compareAndSet(true, false)) {
                    // 已强制下发了前 N 个字符，只需下发剩余部分
                    int alreadySent = forcedFinalLength.getAndSet(0);
                    if (text != null && text.length() > alreadySent) {
                        String remainder = text.substring(alreadySent).trim();
                        if (!remainder.isBlank()) {
                            log.info("[AsrSession] force-segment remainder, len={}", remainder.length());
                            callback.onRecognizing(remainder, lang, speakerId, true);
                        }
                    }
                    return;
                }
                callback.onRecognizing(text, lang, speakerId, true);
            });

            conversationTranscriber.canceled.addEventListener((s, e) -> {
                log.warn("[AsrSession] conversation ASR canceled, reason={}, error={}", e.getReason(), e.getErrorDetails());
                if (callback != null) {
                    callback.onError(e.getErrorDetails());
                }
            });

            conversationTranscriber.sessionStopped.addEventListener((s, e) -> closedLatch.countDown());
            conversationTranscriber.startTranscribingAsync();
        }

        private void applyHotwords() {
            if (hotwords == null || hotwords.isEmpty()) {
                log.info("[AsrSession] loaded hotwords, count=0");
                return;
            }
            PhraseListGrammar phraseList = PhraseListGrammar.fromRecognizer(conversationTranscriber);
            for (String hotword : hotwords) {
                if (hotword != null && !hotword.isBlank()) {
                    phraseList.addPhrase(hotword);
                }
            }
            phraseList.setWeight(1.0);
            log.info("[AsrSession] loaded hotwords, count={}", hotwords.size());
        }

        private String resolveSpeakerId(ConversationTranscriptionResult result) {
            String speakerId = result.getSpeakerId();
            if (speakerId == null || speakerId.isBlank()) {
                return Constants.SPEAKER_ID_UNKNOWN;
            }
            return speakerId;
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
            if (recognizer == null) {
                throw BizException.of(ErrorCode.ASR_RECOGNIZE_ERROR, "ConversationTranscriber 不支持 recognizeOnce");
            }

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> result = new AtomicReference<>("");
            AtomicReference<String> lang = new AtomicReference<>("");
            AtomicBoolean done = new AtomicBoolean(false);

            recognizer.recognized.addEventListener((s, e) -> {
                if (!done.compareAndSet(false, true)) return;
                if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                    result.set(e.getResult().getText());
                    lang.set(resolveDetectedLanguage(e.getResult()));
                }
                latch.countDown();
            });
            recognizer.canceled.addEventListener((s, e) -> {
                if (!done.compareAndSet(false, true)) return;
                latch.countDown();
            });

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
                if (conversationTranscriber != null) {
                    conversationTranscriber.stopTranscribingAsync().get(5, TimeUnit.SECONDS);
                } else if (recognizer != null) {
                    recognizer.stopContinuousRecognitionAsync().get(5, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("[AsrSession] stop recognizer error", e);
            }
            if (conversationTranscriber != null) {
                conversationTranscriber.close();
            }
            if (recognizer != null) {
                recognizer.close();
            }
            pushStream.close();
            config.close();
        }
    }

    public interface RecognizerCallback {
        void onRecognizing(String text, String language, String speakerId, boolean isFinal);
        void onError(String errorMessage);
    }
}
